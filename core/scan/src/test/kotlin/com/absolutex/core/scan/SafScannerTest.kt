package com.absolutex.core.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SafScannerTest {

    private fun dir(uri: String, name: String = uri) = TreeEntry(uri, name, isDirectory = true)
    private fun file(uri: String, name: String, size: Long = 10) =
        TreeEntry(uri, name, isDirectory = false, sizeBytes = size)

    private fun tree(vararg entries: Pair<String, List<TreeEntry>>): DocumentTree {
        val map = entries.toMap()
        return DocumentTree { parent -> map[parent].orEmpty() }
    }

    private fun scanAll(root: TreeEntry, tree: DocumentTree) = runBlocking { SafScanner.scan(root, tree).toList() }

    @Test fun `containers anywhere in the tree are books`() {
        val books = scanAll(
            dir("root"),
            tree(
                "root" to listOf(file("u1", "Batman 001.cbz", 100), dir("sub", "Series")),
                "sub" to listOf(file("u2", "Batman 002.cbr", 200)),
            ),
        )
        assertEquals(listOf("u1", "u2"), books.map { it.path })
        assertEquals(listOf(100L, 200L), books.map { it.sizeBytes })
    }

    @Test fun `a container's displayName is the tree entry's name, not its Uri`() {
        // The Uri DocumentsContract hands back is one percent-encoded segment, e.g.
        // ".../document/primary%3AComics%2FBatman%20001.cbz" — nothing a reader would show, and
        // nothing that matches the name the reader computes from OpenableColumns.DISPLAY_NAME.
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AComics/document/" +
            "primary%3AComics%2FBatman%20001.cbz"
        val books = SafScanner.scan(dir("root"), tree("root" to listOf(file(uri, "Batman 001.cbz", 100))))
        val book = books.single()
        assertEquals(uri, book.path)
        assertEquals("Batman 001.cbz", book.displayName)
    }

    @Test fun `an image-folder book's displayName is the folder's name, not its Uri`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AComics/document/" +
            "primary%3AComics%2FChapter%201"
        val books = SafScanner.scan(
            dir("root"),
            tree(
                "root" to listOf(dir(uri, "Chapter 1")),
                uri to listOf(file("p1", "001.jpg", 5), file("p2", "002.jpg", 7)),
            ),
        )
        val book = books.single()
        assertEquals(uri, book.path)
        assertEquals("Chapter 1", book.displayName)
    }

    @Test fun `junk and hidden entries are skipped, exactly as a filesystem scan skips them`() {
        val books = scanAll(
            dir("root"),
            tree(
                "root" to listOf(dir("mac", "__MACOSX"), dir("hidden", ".trash"), file("u1", "Thumbs.db")),
                "mac" to listOf(file("u2", "Batman.cbz")),
                "hidden" to listOf(file("u3", "Batman.cbz")),
            ),
        )
        assertTrue(books.isEmpty())
    }

    @Test fun `a folder of loose images is one book, not a folder to descend into`() {
        val books = scanAll(
            dir("root"),
            tree(
                "root" to listOf(dir("pages", "Chapter 1")),
                "pages" to listOf(file("p1", "001.jpg", 5), file("p2", "002.jpg", 7)),
            ),
        )
        assertEquals(1, books.size)
        assertEquals("pages", books[0].path)
        assertTrue(books[0].isImageFolder)
        assertEquals(2, books[0].imageCount)
        assertEquals(12L, books[0].sizeBytes)
    }

    @Test fun `one image is not a book`() {
        val books = scanAll(
            dir("root"),
            tree("root" to listOf(dir("pages")), "pages" to listOf(file("p1", "001.jpg"))),
        )
        assertTrue(books.isEmpty())
    }

    @Test fun `a tree that contains itself ends instead of recursing forever`() {
        val books = scanAll(
            dir("root"),
            tree("root" to listOf(dir("root"), file("u1", "Batman.cbz"))),
        )
        assertEquals(listOf("u1"), books.map { it.path })
    }

    @Test fun `books are emitted as they are found, not after the whole tree is walked`() = runBlocking {
        // Five sibling directories, each with its own book; a collector that stops after the
        // first emission proves the walk did not have to visit every sibling first.
        val visited = AtomicInteger()
        val entries = (0 until 5).map { i -> dir("sub$i") }
        val childrenOf = mutableMapOf("root" to entries)
        entries.forEachIndexed { i, d -> childrenOf[d.uri] = listOf(file("book$i", "Batman $i.cbz")) }
        val countingTree = DocumentTree { parent ->
            visited.incrementAndGet()
            childrenOf[parent].orEmpty()
        }

        val first = SafScanner.scan(dir("root"), countingTree).first()

        assertEquals("book0", first.path)
        // root + the one subdirectory the first book came from: the other four were never queried.
        assertTrue("expected an early stop, but visited $visited directories", visited.get() < entries.size)
    }

    @Test fun `a cancelled scan stops promptly instead of finishing the whole tree`() = runBlocking {
        val total = 3_000
        val childrenOf = mutableMapOf("root" to (0 until total).map { i -> dir("sub$i") })
        (0 until total).forEach { i -> childrenOf["sub$i"] = listOf(file("book$i", "Batman $i.cbz")) }
        val slowTree = DocumentTree { parent -> childrenOf[parent].orEmpty() }

        val seen = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            SafScanner.scan(dir("root"), slowTree).collect {
                seen.incrementAndGet()
                delay(1) // a slow consumer, so cancellation reliably lands mid-walk
            }
        }
        while (seen.get() < 5) delay(5)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("cancelled scan kept going: ${seen.get()}", seen.get() < total)
    }
}
