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
 *
 * The event type itself lives in `LibraryChange.kt`, one declaration for every producer.
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

    /**
     * Forgets every folder-book under [dir], which is leaving the tree.
     *
     * Without this, a folder deleted and re-created elsewhere carries its old book state into the
     * new location and the next image inside it reads as a modification of a book the library no
     * longer has — a `Modified` for a path that was never `Added`.
     */
    fun forget(dir: File) {
        val root = canonicalOf(dir)
        val prefix = root + File.separator
        folderIsBook.removeAll { it == root || it.startsWith(prefix) }
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

/**
 * The one shape a delta cannot express: a directory that left the tree.
 *
 * (Implemented in `core/scan/DirectoryDepartures.kt`, next to this file, because both the
 * buffered flush and the poll loop answer it and the two must answer the same way.) A folder move
 * or rename produces a single `ENTRY_DELETE` for the directory on its parent's key — nothing
 * names the books inside it, so their rows would stay in the database at paths that no longer
 * exist — and arriving as create+delete it would also synthesise every one of those books again
 * under the new name. One walk of the location settles both.
 */

private fun canonicalOf(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

/**
 * Recursive filesystem watch over the library roots.
 *
 * Platform notes: this watches the filesystem, so SAF-only locations (no filesystem events)
 * are an explicit limitation — document, don't fake. A SAF tree Uri is observed by re-listing it
 * after `registerContentObserver` on the tree's document Uri, and that is a follow-up: until it
 * exists, those locations are refreshed by [LibraryChange.RescanRequested] on demand rather than
 * by a watcher. Watching `MediaStore.Files` volume-wide is NOT that follow-up — see PR #18.
 *
 * A directory that leaves the tree — deleted, or renamed — is answered with one
 * [LibraryChange.RescanRequested] instead of a delta: nothing names the books inside it, and a
 * rename would otherwise re-announce every one of them under a new path while their old rows
 * stayed in the database.
 *
 * @param roots directories to watch. Watched in the caller's scope: [watch] is cold and
 *   cleanup rides on collection cancellation.
 * @param bufferSize how many changes may be in flight before the watcher asks for a rescan
 *   rather than losing them. Tests pass 1 to reach that path deterministically.
 */
class LibraryWatcher(
    private val roots: List<File>,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val includeHidden: Boolean = false,
    private val bufferSize: Int = LibraryScanner.CHANNEL_CAPACITY,
) : Closeable {

    // (The per-collection state — Pending, WatchState and their members — is declared as internal
    // members here rather than as file-level declarations, solely so the departure handling in
    // DirectoryDepartures can share them. Kotlin forbids an internal member from using a private
    // type, so going the other way would have meant demoting the interesting logic instead.)
    internal data class Pending(val first: WatchEvent.Kind<*>, val last: WatchEvent.Kind<*>, val file: File)

    internal class WatchState(
        val service: WatchService,
        val keys: MutableMap<WatchKey, Path>,
        val seen: MutableSet<String>,
        val classifier: BookEventClassifier,
    ) {

            /** Canonical paths of the directories actually registered, for departure detection. */
        val registered: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** Directories we could not register. See [LibraryWatcher.registerOne]. */
        val failed = AtomicInteger(0)

        fun isRegistered(dir: File): Boolean = registered.contains(canonicalOf(dir))
    }

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
            var overflowRetry: Job? = null
            /**
             * Sends one change, and asks for a rescan if there was no room for it.
             *
             * `trySend` failing means the event is *dropped*, not delayed: the buffer is full
             * because the consumer is behind — a bulk copy landing while the repository writes —
             * and nothing replays it. The delta is then incomplete, so one rescan stands in for
             * everything that did not fit. It must not be dropped either, hence the retry loop.
             */
            fun emit(change: LibraryChange) {
                if (trySend(change).isSuccess) return
                overflowRetry?.cancel()
                overflowRetry = launch {
                    while (isActive && trySend(LibraryChange.RescanRequested).isFailure) {
                        delay(RETRY_MS)
                    }
                }
            }
            fun enqueue(kind: WatchEvent.Kind<*>, file: File) {
                // The flush job is cancelled and replaced under the same lock that guards
                // `pending`: read from the poller thread and written from the flush coroutine,
                // an unsynchronised `flush` could have cancelled a flush that had just started.
                synchronized(pending) {
                    // Last write wins per path, so a burst of temp+rename writes classifies once.
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
                    flush?.cancel()
                    flush = launch {
                        delay(debounceMs)
                        val batch = synchronized(pending) {
                            pending.values.toList().also { pending.clear() }
                        }
                        classifyBatch(batch, state, ::enqueue, ::emit)
                    }
                }
            }
            // A subtree we could not register is a subtree whose events we will never see. Say so
            // instead of streaming a delta with a branch missing from it: inotify's watch limit is
            // per user, shared with MediaProvider, and reached on a large comic library.
            if (state.failed.get() > 0) emit(LibraryChange.RescanRequested)
            // Blocking take() lives on a child of the caller's scope: cancelling collection
            // cancels the poll, and closing the service unblocks the take.
            val poller = launch(Dispatchers.IO) { pollLoop(state, ::enqueue, ::emit) }
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
    }.buffer(bufferSize)

    private fun classifyBatch(
        batch: List<Pending>,
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
        emit: (LibraryChange) -> Unit,
    ) {
        // One coalesced batch can hold one departure at most from the watch's own point of view:
        // a directory that left the tree names no file inside it, so the books under it would stay
        // in the database at paths that no longer exist; and a rename arrives as create+delete, so
        // classifying the new name's contents as arrivals would announce every one of them *again*.
        // One walk of the location settles both, so for this batch it is the whole answer.
        val departed = DirectoryDepartures.take(batch, state)
        if (departed != null) {
            registeredDirs.addAndGet(-departed.cancelled)
            // Anything new in the same burst (the other half of a rename) is still watched, so
            // later changes are seen; its contents are left to the rescan rather than announced.
            for (entry in batch) {
                if (resolveEffectiveKind(entry.first, entry.last) == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerIfDir(entry.file, state, enqueue, synthesize = false)
                }
            }
            emit(LibraryChange.RescanRequested)
            return
        }
        for (entry in batch) {
            val effective = resolveEffectiveKind(entry.first, entry.last) ?: continue
            // A subdir born just before the flush still gets registered; its later events
            // arrive normally and the seed keeps its folder state honest.
            if (effective == StandardWatchEventKinds.ENTRY_CREATE &&
                !registerIfDir(entry.file, state, enqueue)
            ) {
                // Unwatched subtree: the consumer has to know its delta is incomplete.
                emit(LibraryChange.RescanRequested)
            }
            state.classifier.onRaw(effective, entry.file)?.let(emit)
        }
    }

    /**
     * Forgets every directory that is gone. Moved to [DirectoryDepartures.retireAll]: see its KDoc
     * for why the buffered flush and the dead-key path in [pollLoop] share one implementation.
     */
    private fun CoroutineScope.pollLoop(
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
        emit: (LibraryChange) -> Unit,
    ) {
        while (isActive) {
            val key = nextKey(state.service) ?: break
            val dir = synchronized(state.keys) { state.keys[key] }
            if (dir == null) {
                key.cancel()
            } else {
                for (event in key.pollEvents()) handleEvent(event, dir, state, enqueue, emit)
                if (!key.reset()) {
                    synchronized(state.keys) { state.keys.remove(key) }
                    // The watched directory itself is gone, so nothing inside it can be trusted
                    // and no per-file event will name what it held. Same answer as a departure
                    // seen on the parent's key: retire the subtree and re-walk.
                    val retired = DirectoryDepartures.retireAll(listOf(dir.toFile()), state)
                    registeredDirs.addAndGet(-retired)
                    emit(LibraryChange.RescanRequested)
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
        emit: (LibraryChange) -> Unit,
    ) {
        val kind = event.kind()
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            enqueue(kind, dir.toFile())
            return
        }
        @Suppress("UNCHECKED_CAST")
        val child = dir.resolve(event.context() as Path).toFile()
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && !registerIfDir(child, state, enqueue)) {
            emit(LibraryChange.RescanRequested)
        }
        enqueue(kind, child)
    }

    private fun registerIfDir(
        dir: File,
        state: WatchState,
        enqueue: (WatchEvent.Kind<*>, File) -> Unit,
        synthesize: Boolean = true,
    ): Boolean {
        if (!dir.isDirectory) return true
        val fresh = registerAll(dir, state)
        // Nothing registered for a directory we would watch: the platform refused it, and its
        // subtree will stay invisible. (A skipped directory is a deliberate miss, not a failure.)
        if (!state.isRegistered(dir) && !LibraryScanner.shouldSkip(dir, includeHidden)) return false
        // A late registration can postdate the files that triggered it (an extractor writes
        // dir+files in one burst, faster than registration), so seed as absent and reconcile:
        // without this, files born before their dir's watch are invisible on every platform.
        // It is skipped for a directory that is merely new *here* — a rename, whose contents the
        // caller is answering with a rescan instead.
        if (synthesize) {
            for (freshDir in fresh) {
                state.classifier.seedAbsent(freshDir)
                synthesizeArrivals(freshDir, enqueue)
            }
        }
        return true
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
            // Only a directory that registered successfully counts as watched; the departure
            // detection reads this, and a failed registration is reported to the consumer.
            state.registered.add(canonicalOf(dir))
            registeredDirs.incrementAndGet()
            // Re-lists the directory just read: one extra readdir per dir at startup, so no
            // child inventory is kept in memory.
            dir.listFiles()?.filter { it.isDirectory }
        } catch (_: IOException) {
            state.failed.incrementAndGet()
            null
        } catch (_: SecurityException) {
            state.failed.incrementAndGet()
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

        /**
         * How long to wait before trying again to deliver a rescan the buffer had no room for.
         * Only reached when the consumer is already behind, so it trades latency on a bad path
         * for never losing the event that says the delta is incomplete.
         */
        internal const val RETRY_MS = 250L

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
