package com.absolutex.core.scan

import com.absolutex.source.EntryFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Book-level filesystem events: the library updates on add/delete/move without a manual
 * rescan (§5.1). Raw paths are classified with the scanner's own predicates
 * ([LibraryScanner.CONTAINER_EXTENSIONS], [LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK],
 * [EntryFilter]), so watch and scan never disagree on what a book is.
 */
sealed interface LibraryChange {
    data class Added(val path: String) : LibraryChange
    data class Removed(val path: String) : LibraryChange
    data class Modified(val path: String) : LibraryChange

    /** A folder crossed the scanner's image-folder rule and became a book. */
    data class FolderPromoted(val path: String, val imageCount: Int) : LibraryChange

    /** The platform dropped events; the consumer must re-walk instead of trusting the delta. */
    data object RescanRequested : LibraryChange
}

/**
 * Decides what one raw watch event means at book level. Pure over the filesystem except for
 * the folder-book set, which [seed] initialises from the pre-watch walk so the first event
 * on an existing book reads as a modification rather than a promotion.
 */
internal class BookEventClassifier(private val includeHidden: Boolean) {

    private data class FolderSnapshot(val imageCount: Int, val hasVisibleSubdir: Boolean)

    private val folderIsBook = HashSet<String>()

    private fun snapshot(dir: File): FolderSnapshot {
        var images = 0
        var hasSubdir = false
        for (child in dir.listFiles() ?: return FolderSnapshot(0, false)) {
            if (!LibraryScanner.shouldSkip(child, includeHidden)) {
                if (child.isDirectory) hasSubdir = true
                else if (EntryFilter.isPage(child.name)) images++
            }
        }
        return FolderSnapshot(images, hasSubdir)
    }

    fun seed(dir: File) {
        val key = canonicalOf(dir)
        if (isFolderBook(snapshot(dir))) folderIsBook.add(key) else folderIsBook.remove(key)
    }

    /** A newly watched dir starts as not-a-book, so its pre-existing files classify as arrivals. */
    fun seedAbsent(dir: File) {
        folderIsBook.remove(canonicalOf(dir))
    }

    private fun isFolderBook(snapshot: FolderSnapshot): Boolean =
        !snapshot.hasVisibleSubdir && snapshot.imageCount >= LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK

    fun onRaw(kind: WatchEvent.Kind<*>, file: File): LibraryChange? {
        // Inotify queues overflow under bulk copies and the lost events cannot be
        // reconstructed, so the only correct answer is a re-walk.
        if (kind == StandardWatchEventKinds.OVERFLOW) return LibraryChange.RescanRequested
        if (isIgnored(file)) return null
        if (kind != StandardWatchEventKinds.ENTRY_DELETE && file.isDirectory) return null
        if (kind == StandardWatchEventKinds.ENTRY_DELETE && folderIsBook.remove(canonicalOf(file))) {
            return LibraryChange.Removed(file.path)
        }
        // Same extension set the scanner walks, not a copy of it.
        if (isContainer(file)) return containerEvent(kind, file)
        return folderEvent(file)
    }

    private fun isIgnored(file: File): Boolean =
        LibraryScanner.shouldSkip(file, includeHidden) || isUnderSkippedDir(file)

    private fun isContainer(file: File): Boolean =
        EntryFilter.extensionOf(file.name) in LibraryScanner.CONTAINER_EXTENSIONS

    private fun containerEvent(kind: WatchEvent.Kind<*>, file: File): LibraryChange = when (kind) {
        StandardWatchEventKinds.ENTRY_CREATE -> LibraryChange.Added(file.path)
        StandardWatchEventKinds.ENTRY_DELETE -> LibraryChange.Removed(file.path)
        else -> LibraryChange.Modified(file.path)
    }

    private fun folderEvent(file: File): LibraryChange? {
        if (!EntryFilter.isPage(file.name)) return null
        // An image matters only through its folder: one added to a 1-image folder promotes it.
        val parent = file.parentFile ?: return null
        val snapshot = snapshot(parent)
        val key = canonicalOf(parent)
        val was = key in folderIsBook
        val isNow = isFolderBook(snapshot)
        if (isNow) folderIsBook.add(key) else folderIsBook.remove(key)
        return when {
            isNow && !was -> LibraryChange.FolderPromoted(parent.path, snapshot.imageCount)
            !isNow && was -> LibraryChange.Removed(parent.path)
            isNow -> LibraryChange.Modified(parent.path)
            else -> null
        }
    }

    private fun isUnderSkippedDir(file: File): Boolean {
        // Ancestor walk only, depth-bounded by the tree, and past the name checks above.
        var parent = file.parentFile
        while (parent != null) {
            if (LibraryScanner.shouldSkip(parent, includeHidden)) return true
            parent = parent.parentFile
        }
        return false
    }
}

private fun canonicalOf(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

/**
 * Recursive filesystem watch over the library roots.
 *
 * Platform notes: this watches the filesystem, so SAF-only locations (no filesystem events)
 * are an explicit limitation — document, don't fake.
 * TODO(library-watcher): a MediaStore observer as the follow-up for SAF locations; wire
 * [LibraryChange] into LibraryRepository (Added/Modified → upsert scan, Removed → delete,
 * RescanRequested → scanLocation re-walk). Owned by the scan lane; the repository lane
 * owns the wiring.
 *
 * @param roots directories to watch. Watched in the caller's scope: [watch] is cold and
 *   cleanup rides on collection cancellation.
 */
class LibraryWatcher(
    private val roots: List<File>,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val includeHidden: Boolean = false,
) : Closeable {

    private data class Pending(val first: WatchEvent.Kind<*>, val last: WatchEvent.Kind<*>, val file: File)

    private class WatchState(
        val service: WatchService,
        val keys: MutableMap<WatchKey, Path>,
        val seen: MutableSet<String>,
        val classifier: BookEventClassifier,
    )

    private val closed = AtomicBoolean(false)
    private val liveServices = ConcurrentHashMap.newKeySet<WatchService>()
    private val registeredDirs = AtomicInteger(0)

    /** How many directories are currently registered. Lets callers await a live watch before acting. */
    internal fun registeredDirectoryCount(): Int = registeredDirs.get()

    /** Cold: nothing is watched until collected, and collection in a scope the caller owns. */
    fun watch(): Flow<LibraryChange> = callbackFlow {
        // callbackFlow fails a block that returns with the channel still open, so the closed
        // path closes it explicitly instead of reaching for awaitClose, which would hang.
        if (closed.get()) {
            // A closed watcher is already empty: complete at once with nothing emitted.
            close()
        } else {
            val state = WatchState(
                FileSystems.getDefault().newWatchService(),
                HashMap(),
                HashSet(),
                BookEventClassifier(includeHidden),
            )
            liveServices.add(state.service)
            for (root in roots) {
                val fresh = registerAll(root, state)
                for (freshDir in fresh) state.classifier.seed(freshDir)
            }
            val pending = LinkedHashMap<String, Pending>()
            var flush: Job? = null
            fun enqueue(kind: WatchEvent.Kind<*>, file: File) {
                // Last write wins per path, so a burst of temp+rename writes classifies once.
                synchronized(pending) {
                    val key = if (kind == StandardWatchEventKinds.OVERFLOW) {
                        OVERFLOW_KEY
                    } else {
                        canonicalOf(file)
                    }
                    val prior = pending[key]
                    if (prior == null) {
                        pending[key] = Pending(kind, kind, file)
                    } else {
                        pending[key] = prior.copy(last = kind, file = file)
                    }
                }
                flush?.cancel()
                flush = launch {
                    delay(debounceMs)
                    val batch = synchronized(pending) {
                        pending.values.toList().also { pending.clear() }
                    }
                    classifyBatch(batch, state, ::enqueue) { trySend(it) }
                }
            }
            // Blocking take() lives on a child of the caller's scope: cancelling collection
            // cancels the poll, and closing the service unblocks the take.
            val poller = launch(Dispatchers.IO) { pollLoop(state, ::enqueue) }
            try {
                awaitClose {
                    poller.cancel()
                    liveServices.remove(state.service)
                    runCatching { state.service.close() }
                }
            } finally {
                // Leaked inotify instances exhaust fds: always close, even if registration throws.
                poller.cancel()
                liveServices.remove(state.service)
                runCatching { state.service.close() }
            }
        }
        // Bounded like the scanner's channel: a cancelled collector stops promptly instead
        // of draining a backlog.
    }.buffer(LibraryScanner.CHANNEL_CAPACITY)

    private fun classifyBatch(
        batch: List<Pending>,
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
        send: (LibraryChange) -> Unit,
    ) {
        for (entry in batch) {
            val effective = resolveEffectiveKind(entry.first, entry.last) ?: continue
            // A subdir born just before the flush still gets registered; its later events
            // arrive normally and the seed keeps its folder state honest.
            if (effective == StandardWatchEventKinds.ENTRY_CREATE) {
                registerIfDir(entry.file, state, enqueue)
            }
            state.classifier.onRaw(effective, entry.file)?.let(send)
        }
    }

    private fun CoroutineScope.pollLoop(
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
    ) {
        while (isActive) {
            val key = nextKey(state.service) ?: break
            val dir = synchronized(state.keys) { state.keys[key] }
            if (dir == null) {
                key.cancel()
            } else {
                for (event in key.pollEvents()) handleEvent(event, dir, state, enqueue)
                if (!key.reset()) {
                    synchronized(state.keys) { state.keys.remove(key) }
                    // A deleted watched dir reports no file event for itself, so synthesise
                    // it here; pending coalescing keeps it to one Removed.
                    enqueue(StandardWatchEventKinds.ENTRY_DELETE, dir.toFile())
                }
            }
        }
    }

    private fun nextKey(service: WatchService): WatchKey? = try {
        service.take()
    } catch (_: ClosedWatchServiceException) {
        null
    }

    private fun handleEvent(
        event: WatchEvent<*>,
        dir: Path,
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
    ) {
        val kind = event.kind()
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            enqueue(kind, dir.toFile())
            return
        }
        @Suppress("UNCHECKED_CAST")
        val child = dir.resolve(event.context() as Path).toFile()
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) registerIfDir(child, state, enqueue)
        enqueue(kind, child)
    }

    private fun registerIfDir(
        dir: File,
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
    ) {
        if (!dir.isDirectory) return
        val fresh = registerAll(dir, state)
        // A late registration can postdate the files that triggered it (an extractor writes
        // dir+files in one burst, faster than registration), so seed as absent and reconcile:
        // without this, files born before their dir's watch are invisible on every platform.
        for (freshDir in fresh) {
            state.classifier.seedAbsent(freshDir)
            synthesizeArrivals(freshDir, enqueue)
        }
    }

    /**
     * Reconciles files already inside a newly watched dir as arrivals. Containers arrive one
     * by one (each is its own book); images arrive as a single folder evaluation, because the
     * live snapshot already counts every image and replaying each would spam Modified.
     */
    private fun synthesizeArrivals(
        dir: File,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
    ) {
        // Sorted for determinism: a folder's synthesis always evaluates the same first image.
        val files = dir.listFiles()
            ?.filter { it.isFile && !LibraryScanner.shouldSkip(it, includeHidden) }
            ?.sortedBy { it.name }
            ?: return
        val containers = files.filter {
            EntryFilter.extensionOf(it.name) in LibraryScanner.CONTAINER_EXTENSIONS
        }
        for (file in containers) {
            enqueue(StandardWatchEventKinds.ENTRY_CREATE, file)
        }
        files.firstOrNull { EntryFilter.isPage(it.name) }?.let {
            enqueue(StandardWatchEventKinds.ENTRY_CREATE, it)
        }
    }

    /**
     * Registers [root] and every non-skipped subtree dir, returning the newly watched dirs in
     * parents-first order for seeding.
     */
    private fun registerAll(root: File, state: WatchState): List<File> {
        val fresh = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            registerOne(dir, state)?.let {
                fresh.add(dir)
                stack.addAll(it)
            }
        }
        return fresh
    }

    /**
     * Registers one dir, returning its subdirs to descend into. Null skips the subtree:
     * already seen (the symlink-cycle guard, same argument as the scanner walk), skipped
     * like the scan, or unreadable mid-walk (stays unwatched until the next rescan).
     */
    private fun registerOne(dir: File, state: WatchState): List<File>? {
        if (!state.seen.add(canonicalOf(dir))) return null
        if (LibraryScanner.shouldSkip(dir, includeHidden)) return null
        return try {
            val path = dir.toPath()
            val key = path.register(
                state.service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
            synchronized(state.keys) { state.keys[key] = path }
            registeredDirs.incrementAndGet()
            // Re-lists the directory just read: one extra readdir per dir at startup, so no
            // child inventory is kept in memory.
            dir.listFiles()?.filter { it.isDirectory }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Idempotent: safe to call twice, and safe after every service already closed. */
    override fun close() {
        if (closed.compareAndSet(false, true)) liveServices.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Quiet period before a burst of raw events is classified and emitted.
         * Editors and extractors write temp+rename bursts; 500ms-scale absorbs them without
         * making adds feel laggy. TODO(library-watcher): tune the delay on device.
         */
        const val DEBOUNCE_MS = 500L

        // Not an absolute path, so it never collides with a real pending key: overflows
        // coalesce with each other and nothing else.
        internal const val OVERFLOW_KEY = "@watcher-overflow"

        /**
         * Merges the first and last kind seen for one path in a burst. A file created and
         * deleted within one burst never existed as a book and emits nothing; a file created
         * then modified still reads as added, so rapid saves don't flip Added into Modified.
         */
        internal fun resolveEffectiveKind(
            first: WatchEvent.Kind<*>,
            last: WatchEvent.Kind<*>,
        ): WatchEvent.Kind<*>? {
            if (last == StandardWatchEventKinds.OVERFLOW) return last
            if (first == StandardWatchEventKinds.ENTRY_CREATE && last == StandardWatchEventKinds.ENTRY_DELETE) {
                return null
            }
            if (first == StandardWatchEventKinds.ENTRY_CREATE) return StandardWatchEventKinds.ENTRY_CREATE
            return last
        }
    }
}
