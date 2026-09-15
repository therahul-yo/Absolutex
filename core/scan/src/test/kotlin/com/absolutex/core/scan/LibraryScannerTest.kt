package com.absolutex.core.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LibraryScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, bytes: Int = 16): File {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    private fun scanAll(vararg roots: File, parallelism: Int = 4) = runBlocking {
        LibraryScanner(parallelism = parallelism).scan(roots.toList()).toList()
    }

    @Test fun `finds containers anywhere in the tree`() {
        file("Batman/Batman 001.cbz")
        file("Batman/deep/nest/Batman 002.cbr")
        file("Manga/Kaiju No. 8 v01.cb7")
        file("notes.txt")
        val found = scanAll(tmp.root).map { File(it.path).name }.sorted()
        assertEquals(listOf("Batman 001.cbz", "Batman 002.cbr", "Kaiju No. 8 v01.cb7"), found)
    }

    @Test fun `parses metadata while scanning`() {
        file("Spider-Man 2099/001.cbz")
        val book = scanAll(tmp.root).single()
        assertEquals("Spider-Man 2099", book.parsed.series)
        assertEquals(1.0, book.parsed.issue!!.value, 0.0)
    }

    @Test fun `a folder of loose images is a book`() {
        file("Loose Pages/001.jpg")
        file("Loose Pages/002.jpg")
        val book = scanAll(tmp.root).single()
        assertTrue(book.isImageFolder)
        assertEquals(2, book.imageCount)
        assertEquals("Loose Pages", book.parsed.series)
    }

    @Test fun `a single image is not a book`() {
        file("Almost/001.jpg")
        assertEquals(0, scanAll(tmp.root).size)
    }

    @Test fun `a folder with subfolders is not an image-folder book`() {
        file("Parent/001.jpg")
        file("Parent/002.jpg")
        file("Parent/child/003.jpg")
        file("Parent/child/004.jpg")
        // Parent has a subfolder, so only the leaf qualifies.
        val books = scanAll(tmp.root)
        assertEquals(1, books.size)
        assertEquals("child", File(books.single().path).name)
    }

    @Test fun `a junk directory is not descended into`() {
        // Filtering junk file NAMES is not enough: "__MACOSX/001.cbz" has an innocent filename.
        file("Book/__MACOSX/001.cbz")
        file("Book/Real 001.cbz")
        assertEquals(listOf("Real 001.cbz"), scanAll(tmp.root).map { File(it.path).name })
    }

    @Test fun `junk and hidden entries are ignored`() {
        file("Book/__MACOSX/001.cbz")
        file("Book/.hidden/002.cbz")
        file("Book/.secret.cbz")
        file("Book/Thumbs.db")
        file("Book/Real 001.cbz")
        val found = scanAll(tmp.root).map { File(it.path).name }
        assertEquals(listOf("Real 001.cbz"), found)
    }

    @Test fun `overlapping roots emit each book once`() {
        file("Shared/Batman 001.cbz")
        val nested = File(tmp.root, "Shared")
        assertEquals(1, scanAll(tmp.root, nested).size)
    }

    @Test fun `a symlink cycle does not hang the walk`() {
        file("Real/Batman 001.cbz")
        val loop = File(tmp.root, "Real/loop").toPath()
        // A link pointing at an ancestor: without a canonical-path guard this recurses forever.
        Files.createSymbolicLink(loop, tmp.root.toPath())
        val books = runBlocking {
            withTimeout(20_000) { LibraryScanner(parallelism = 4).scan(listOf(tmp.root)).toList() }
        }
        assertEquals(1, books.size)
    }

    @Test fun `cancellation stops the scan promptly`() = runBlocking {
        repeat(3_000) { file("many/book $it.cbz") }
        val seen = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            LibraryScanner(parallelism = 4).scan(listOf(tmp.root)).collect {
                seen.incrementAndGet()
                delay(1)   // slow consumer, so cancellation lands mid-scan
            }
        }
        while (seen.get() < 5) delay(5)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("cancelled scan kept going: ${seen.get()}", seen.get() < 3_000)
    }

    @Test fun `five thousand files scan well inside the budget`() {
        // §3: 5,000 files in under 15 s. Generous here because CI hardware is slower than the
        // reference device's UFS storage; a regression that matters blows past this anyway.
        repeat(5_000) { file("lib/series ${it % 50}/issue $it.cbz", bytes = 1) }
        val started = System.nanoTime()
        val count = runBlocking {
            LibraryScanner(parallelism = 4).scan(listOf(tmp.root)).count()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("SCAN_BUDGET 5000 files in ${elapsedMs}ms")
        assertEquals(5_000, count)
        assertTrue("5000 files took ${elapsedMs}ms, budget 15000ms", elapsedMs < 15_000)
    }
}
