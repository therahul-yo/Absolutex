package com.absolutex.core.scan

import java.io.File
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey

/**
 * What one departure took with it: the directory that left, and how many watch keys pointed
 * into its subtree.
 */
internal data class Departure(val dirs: List<File>, val cancelled: Int)

/**
 * The one shape a delta cannot express: a directory that left the tree.
 *
 * A folder move or rename produces a single `ENTRY_DELETE` for the directory on its parent's key
 * — nothing names the books inside it, so their rows would stay in the database at paths that no
 * longer exist — and arriving as create+delete it would also synthesise every one of those books
 * again under the new name. One walk of the location settles both.
 *
 * Its own file because two paths answer it — the buffered flush and the dead-key path, which run
 * in different coroutine scopes — yet the two must answer it identically. It works on the same
 * [LibraryWatcher.Pending] queue and [LibraryWatcher.WatchState] the watcher does, so what it
 * touches is explicit rather than a second implementation of the same bookkeeping.
 */
internal object DirectoryDepartures {

    /**
     * Pulls the directory departures out of one coalesced batch: all of them, since a burst can
     * move several folders at once and each one left behind keys still pointing at its old path.
     *
     * An entry counts only when the batch's own effective kind for it is a delete of a directory
     * this watch knew: a create-then-delete pair — the coalescer settles those to nothing — never
     * existed as far as anyone can tell, so it is not a departure.
     */
    fun take(
        batch: List<LibraryWatcher.Pending>,
        state: LibraryWatcher.WatchState,
    ): Departure? {
        val departed = batch.filter { entry ->
            state.registered.contains(canonicalOf(entry.file)) &&
                LibraryWatcher.resolveEffectiveKind(entry.first, entry.last) ==
                StandardWatchEventKinds.ENTRY_DELETE
        }.map { it.file }
        if (departed.isEmpty()) return null
        return Departure(departed, retireAll(departed, state))
    }

    /**
     * Forgets every directory that is gone, and everything the watcher knew about it.
     *
     * A watch key over a directory that no longer exists cannot be reset; worse, after a rename
     * inotify keeps a key pointed at the *old* path, so every later event inside the folder would
     * resolve to a path that is gone. Registration, cycle-guard membership and folder-book state
     * go with the keys, so a directory that reappears under a new name is watched and classified
     * from scratch. Returns how many keys were cancelled.
     */
    fun retireAll(
        dirs: List<File>,
        state: LibraryWatcher.WatchState,
    ): Int {
        val roots = dirs.map { canonicalOf(it) }
        val prefixes = roots.map { it + File.separator }
        fun isGone(canonical: String): Boolean =
            canonical in roots || prefixes.any { canonical.startsWith(it) }
        var cancelled = 0
        synchronized(state.keys) {
            val keys = state.keys.entries.iterator()
            while (keys.hasNext()) {
                val (key, path) = keys.next()
                // Keys hold the path as registered, which may run through a symlink (/sdcard);
                // the roots are canonical, so compare like with like.
                if (isGone(canonicalOf(path.toFile()))) {
                    key.cancel()
                    keys.remove()
                    cancelled++
                }
            }
        }
        state.registered.removeAll(::isGone)
        state.seen.removeAll(::isGone)
        dirs.forEach(state.classifier::forget)
        return cancelled
    }
}

private fun canonicalOf(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
