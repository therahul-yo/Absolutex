package com.absolutex.core.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds

class LibraryWatcherTest {

    @get:Rule val tmp = TemporaryFolder()

    // Filesystem delivery is environment-paced (polling on macOS, inotify on Linux CI), so every
    // wait here is a generous liveness bound with polling asserts — never a timing assertion.
    // Tests await signals (event arrival) rather than delay() against a real clock.
    private val liveTimeoutMs = 15_000L
    // A generous window for cases that genuinely need to prove nothing happened (overflow,
    // silence). Shortening it increases flake rate on loaded CI runners rather than reducing it.
    private val quietMs = 5_000L

    private fun write(path: String, bytes: Int = 32): File {
        val file = File(tmp.root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    private fun mkdir(path: String): File {
        val dir = File(tmp.root, path)
        assertTrue(dir.mkdirs())
        return dir
    }

    private class Sink {
        val events = java.util.Collections.synchronizedList(mutableListOf<LibraryChange>())
        fun collectIn(scope: CoroutineScope, flow: Flow<LibraryChange>): Job =
            scope.launch { flow.collect { events.add(it) } }

        fun snapshot(): List<LibraryChange> = synchronized(events) { events.toList() }
    }

    /** Awaits a signal (CompletableDeferred completed by the sink) rather than polling a real clock. */
    private suspend fun awaitSignal(
        timeoutMs: Long,
        message: String,
        deferred: CompletableDeferred<LibraryChange>,
    ): LibraryChange {
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            // fail() always throws AssertionError; spelling it out gives this branch type
            // Nothing, which is what makes the try/catch expression typecheck as LibraryChange.
            throw AssertionError(message)
        }
    }

    private fun awaitTrue(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(50)
        }
    }

    private fun awaitEvent(timeoutMs: Long, sink: Sink, predicate: (LibraryChange) -> Boolean): LibraryChange {
        var found: LibraryChange? = null
        awaitTrue(timeoutMs, "timed out waiting for event; got ${sink.snapshot()}") {
            sink.snapshot().firstOrNull(predicate)?.also { found = it } != null
        }
        return found!!
    }

    // Registration is asynchronous to the test thread, and a change that lands before a
    // directory's initial snapshot is invisible by design (pre-existing files belong to the
    // initial scan). Acting before the watch is live would race that snapshot and flake.
    private fun awaitReady(watcher: LibraryWatcher, expectedDirs: Int) {
        awaitTrue(
            liveTimeoutMs,
            "watch never registered $expectedDirs dirs; got ${watcher.registeredDirectoryCount()}",
        ) { watcher.registeredDirectoryCount() >= expectedDirs }
    }

    private suspend fun withWatch(
        debounceMs: Long = 80L,
        bufferSize: Int = LibraryScanner.CHANNEL_CAPACITY,
        block: suspend (LibraryWatcher, Sink) -> Unit,
    ) {
        val watcher = LibraryWatcher(listOf(tmp.root), debounceMs = debounceMs, bufferSize = bufferSize)
        val sink = Sink()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = sink.collectIn(scope, watcher.watch())
        try {
            block(watcher, sink)
        } finally {
            job.cancel()
            withTimeout(5_000) { job.join() }
            scope.cancel()
            watcher.close()
        }
    }

    @Test fun `added container is delivered`() = runBlocking {
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            val file = write("Batman/Batman 001.cbz")
            val got = awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Added(file.path) }
            assertEquals(file.path, (got as LibraryChange.Added).path)
        }
    }

    @Test fun `modified container is delivered`() = runBlocking {
        val file = write("Batman/Batman 001.cbz")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            file.appendBytes(ByteArray(64))
            val got = awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Modified(file.path) }
            assertEquals(file.path, (got as LibraryChange.Modified).path)
        }
    }

    @Test fun `deleted container is delivered`() = runBlocking {
        val file = write("Batman/Batman 001.cbz")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            assertTrue(file.delete())
            val got = awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Removed(file.path) }
            assertEquals(file.path, (got as LibraryChange.Removed).path)
        }
    }

    @Test fun `move is delivered as delete plus add`() = runBlocking {
        val from = write("Batman/Batman 001.cbz")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            val to = File(tmp.root, "Batman/Batman 002.cbz")
            Files.move(from.toPath(), to.toPath())
            awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Removed(from.path) }
            awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Added(to.path) }
        }
    }

    @Test fun `second image promotes a one-image folder to a book`() = runBlocking {
        write("Loose/001.jpg")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            write("Loose/002.jpg")
            val got = awaitEvent(liveTimeoutMs, sink) { it is LibraryChange.FolderPromoted }
            val promoted = got as LibraryChange.FolderPromoted
            assertEquals(File(tmp.root, "Loose").path, promoted.path)
            assertEquals(2, promoted.imageCount)
        }
    }

    @Test fun `deleting below the folder rule removes the book`() = runBlocking {
        write("Loose/001.jpg")
        val second = write("Loose/002.jpg")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            assertTrue(second.delete())
            val got = awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Removed(File(tmp.root, "Loose").path) }
            assertEquals(File(tmp.root, "Loose").path, (got as LibraryChange.Removed).path)
        }
    }

    @Test fun `image added to an existing folder book reads as modified`() = runBlocking {
        write("Loose/001.jpg")
        write("Loose/002.jpg")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            write("Loose/003.jpg")
            // Seeded as a book at startup, so this is a modification, not a promotion.
            awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Modified(File(tmp.root, "Loose").path) }
            assertTrue(sink.snapshot().none { it is LibraryChange.FolderPromoted })
        }
    }

    @Test fun `deleting a folder book directory asks for a rescan`() = runBlocking {
        write("Loose/001.jpg")
        write("Loose/002.jpg")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            assertTrue(File(tmp.root, "Loose").deleteRecursively())
            // The directory took its books with it and no event names them individually, so the
            // database drops them in the re-walk rather than keeping stale rows for vanished files.
            awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.RescanRequested }
            val retired = sink.snapshot().filterIsInstance<LibraryChange.Removed>().map { it.path }
            assertTrue(
                "only the folder itself may be retired, never a book inside it: $retired",
                retired.all { it == File(tmp.root, "Loose").path },
            )
        }
    }

    @Test fun `images in a folder with subfolders are not a book`() = runBlocking {
        mkdir("Parent/child")
        withWatch { watcher, sink ->
            awaitReady(watcher, 3)
            write("Parent/001.jpg")
            write("Parent/002.jpg")
            write("Parent/child/003.jpg")
            write("Parent/child/004.jpg")
            write("Parent/005.jpg")
            Thread.sleep(quietMs)
            assertTrue("expected no promotion for Parent, got ${sink.snapshot()}", sink.snapshot().none {
                it is LibraryChange.FolderPromoted && it.path == File(tmp.root, "Parent").path
            })
        }
    }

    @Test fun `a single image is not a book`() = runBlocking {
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            write("Almost/001.jpg")
            Thread.sleep(quietMs)
            assertTrue("expected silence, got ${sink.snapshot()}", sink.snapshot().isEmpty())
        }
    }

    @Test fun `junk and hidden entries produce nothing`() = runBlocking {
        mkdir("Book")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            write("Book/Real 001.cbz")
            awaitEvent(liveTimeoutMs, sink) { it is LibraryChange.Added }
            sink.events.clear()
            write("Book/__MACOSX/001.cbz")
            write("Book/Thumbs.db")
            write("Book/.secret.cbz")
            write("Book/notes.txt")
            write("Book/.hidden/002.cbz")
            Thread.sleep(quietMs)
            assertTrue("expected silence, got ${sink.snapshot()}", sink.snapshot().isEmpty())
        }
    }

    @Test fun `junk directory created after watch starts stays unwatched`() = runBlocking {
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            File(tmp.root, "Book/__MACOSX").mkdirs()
            write("Book/__MACOSX/inner.cbz")
            Thread.sleep(quietMs)
            assertTrue("expected silence, got ${sink.snapshot()}", sink.snapshot().isEmpty())
        }
    }

    @Test fun `new subdirectories are picked up`() = runBlocking {
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            val file = write("New/Deep/Batman 001.cbz")
            val got = awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Added(file.path) }
            assertEquals(file.path, (got as LibraryChange.Added).path)
        }
    }

    @Test fun `images extracted into a new folder promote it`() = runBlocking {
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            // An extractor writes dir+files in one burst, faster than registration: the files
            // predate their dir's watch and only arrive via reconciliation.
            write("Fresh/001.jpg")
            write("Fresh/002.jpg")
            val got = awaitEvent(liveTimeoutMs, sink) { it is LibraryChange.FolderPromoted }
            val promoted = got as LibraryChange.FolderPromoted
            assertEquals(File(tmp.root, "Fresh").path, promoted.path)
            assertEquals(2, promoted.imageCount)
        }
    }

    @Test fun `a directory moved out of the tree asks for a rescan, not stale books`() = runBlocking {
        val dir = mkdir("Batman")
        write("Batman/Batman 001.cbz")
        write("Batman/Batman 002.cbz")
        val outside = Files.createTempDirectory("moved-out").toFile()
        try {
            withWatch { watcher, sink ->
                awaitReady(watcher, 1)
                assertTrue(dir.renameTo(File(outside, "Batman")))
                // A move names the directory and nothing else: no event identifies the books that
                // went with it, so before this the library kept both rows pointing at files that
                // had left. The re-walk is what retires them.
                awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.RescanRequested }
                val retired = sink.snapshot().filterIsInstance<LibraryChange.Removed>().map { it.path }
                assertTrue(
                    "a per-file event claimed a book that merely moved: $retired",
                    retired.all { it.startsWith(dir.path) },
                )
            }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test fun `every directory that leaves in one burst is retired, not just the first`() = runBlocking {
        val batman = mkdir("Batman")
        write("Batman/Batman 001.cbz")
        val superman = mkdir("Superman")
        write("Superman/Superman 001.cbz")
        val outside = Files.createTempDirectory("moved-out").toFile()
        try {
            withWatch { watcher, _ ->
                awaitReady(watcher, 3)
                assertTrue(batman.renameTo(File(outside, "Batman")))
                assertTrue(superman.renameTo(File(outside, "Superman")))
                // Only the root is left in the tree. A moved folder's inotify key survives the move,
                // so a key not retired here would report later events at a path that is gone.
                awaitTrue(liveTimeoutMs, "keys left: ${watcher.registeredDirectoryCount()}") {
                    watcher.registeredDirectoryCount() == 1
                }
            }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test fun `a renamed directory never reports a book at the path that is gone`() = runBlocking {
        val dir = mkdir("Batman")
        write("Batman/Batman 001.cbz")
        withWatch { watcher, sink ->
            awaitReady(watcher, 1)
            val renamed = File(tmp.root, "Batman 2024")
            assertTrue(dir.renameTo(renamed))
            // A rename arrives as delete+create. The delete half is the departure; the create half
            // must not synthesise every book inside as an arrival, which is the duplicate.
            awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.RescanRequested }

            write("Batman 2024/Batman 002.cbz")
            // A settle window rather than a timing assertion: whatever the platform reports, the
            // two assertions below have to hold for all of it.
            Thread.sleep(2_000)
            val stalePath = File(dir, "Batman 002.cbz").path
            val arrivals = sink.snapshot().filterIsInstance<LibraryChange.Added>().map { it.path }
            assertTrue("an event named a path that no longer exists: $arrivals", stalePath !in arrivals)
            assertTrue(
                "an arrival after a rename must name the new directory: $arrivals",
                arrivals.all { it.startsWith(renamed.path) },
            )
        }
    }

    @Test fun `a full buffer asks for a rescan instead of dropping events`() = runBlocking {
        val watcher = LibraryWatcher(listOf(tmp.root), debounceMs = 20L, bufferSize = 1)
        val scope = CoroutineScope(Dispatchers.Default)
        val seen = java.util.Collections.synchronizedList(mutableListOf<LibraryChange>())
        // A collector that stalls on its first event: the slot behind it fills and the rest of the
        // burst has nowhere to go. Those events used to disappear without a word, which is how a
        // bulk copy could leave the library quietly wrong until the next manual rescan.
        val job = scope.launch {
            watcher.watch().collect { change ->
                seen.add(change)
                if (seen.size == 1) delay(3_000)
            }
        }
        try {
            awaitTrue(liveTimeoutMs, "watch never registered") { watcher.registeredDirectoryCount() >= 1 }
            mkdir("Burst")
            for (index in 1..6) write("Burst/Batman 00$index.cbz")
            awaitTrue(liveTimeoutMs, "the buffer filled silently; got ${seen.toList()}") {
                synchronized(seen) { seen.contains(LibraryChange.RescanRequested) }
            }
        } finally {
            job.cancel()
            withTimeout(5_000) { job.join() }
            scope.cancel()
            watcher.close()
        }
    }

    @Test fun `rapid writes coalesce to a bounded burst`() = runBlocking {
        mkdir("burst")
        withWatch { watcher, sink ->
            awaitReady(watcher, 2)
            val file = write("burst/book.cbz", bytes = 8)
            awaitEvent(liveTimeoutMs, sink) { it is LibraryChange.Added }
            sink.events.clear()
            repeat(20) { file.appendBytes(ByteArray(64)) }
            awaitEvent(liveTimeoutMs, sink) { it is LibraryChange.Modified }
            Thread.sleep(1_500)
            val mine = sink.snapshot().filter {
                (it as? LibraryChange.Modified)?.path == file.path
            }
            // Count bounds, not timing: the scheduler may split one burst across two flushes.
            assertTrue("burst of 20 writes produced ${mine.size} events, want at most 2", mine.size <= 2)
        }
    }

    @Test fun `overflow is a rescan request`() {
        // Forced overflow fake: stuffing a real inotify queue is environment-dependent and flaky by nature.
        val classifier = BookEventClassifier(includeHidden = false)
        assertEquals(
            LibraryChange.RescanRequested,
            classifier.onRaw(StandardWatchEventKinds.OVERFLOW, tmp.root),
        )
    }

    @Test fun `create then delete in one burst emits nothing`() {
        assertNull(
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
            ),
        )
    }

    @Test fun `create then modify still reads as added`() {
        assertEquals(
            StandardWatchEventKinds.ENTRY_CREATE,
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            ),
        )
    }

    @Test fun `other kind pairs pass the last write through`() {
        assertEquals(
            StandardWatchEventKinds.ENTRY_MODIFY,
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_MODIFY,
            ),
        )
        assertEquals(
            StandardWatchEventKinds.ENTRY_DELETE,
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            ),
        )
        assertEquals(
            StandardWatchEventKinds.ENTRY_CREATE,
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_CREATE,
            ),
        )
        assertEquals(
            StandardWatchEventKinds.OVERFLOW,
            LibraryWatcher.resolveEffectiveKind(
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.OVERFLOW,
            ),
        )
    }

    @Test fun `a symlink cycle does not hang registration`() = runBlocking {
        write("Real/Batman 001.cbz")
        Files.createSymbolicLink(File(tmp.root, "Real/loop").toPath(), tmp.root.toPath())
        // Same shape as the scanner's cycle test: registration must terminate and the watch must work.
        withTimeout(20_000) {
            withWatch { watcher, sink ->
                awaitReady(watcher, 2)
                val file = write("Real/Batman 002.cbz")
                awaitEvent(liveTimeoutMs, sink) { it == LibraryChange.Added(file.path) }
            }
        }
    }

    @Test fun `cancellation closes the watch promptly`() = runBlocking {
        val watcher = LibraryWatcher(listOf(tmp.root))
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { watcher.watch().collect {} }
        awaitTrue(liveTimeoutMs, "watch never registered") { watcher.registeredDirectoryCount() >= 1 }
        job.cancel()
        withTimeout(5_000) { job.join() }
        scope.cancel()
        watcher.close()
    }

    @Test fun `close is idempotent`() {
        val watcher = LibraryWatcher(listOf(tmp.root))
        watcher.close()
        watcher.close()
    }

    @Test fun `watch after close completes empty`() = runBlocking {
        val watcher = LibraryWatcher(listOf(tmp.root))
        watcher.close()
        assertTrue(watcher.watch().toList().isEmpty())
    }
}
