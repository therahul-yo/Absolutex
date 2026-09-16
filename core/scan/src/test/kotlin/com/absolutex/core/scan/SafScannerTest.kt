package com.absolutex.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafScannerTest {

    private fun dir(uri: String, name: String = uri) = TreeEntry(uri, name, isDirectory = true)
    private fun file(uri: String, name: String, size: Long = 10) =
        TreeEntry(uri, name, isDirectory = false, sizeBytes = size)

    private fun tree(vararg entries: Pair<String, List<TreeEntry>>): DocumentTree {
        val map = entries.toMap()
        return DocumentTree { parent -> map[parent].orEmpty() }
    }

    @Test fun `containers anywhere in the tree are books`() {
        val books = SafScanner.scan(
            dir("root"),
            tree(
                "root" to listOf(file("u1", "Batman 001.cbz", 100), dir("sub", "Series")),
                "sub" to listOf(file("u2", "Batman 002.cbr", 200)),
            ),
        )
        assertEquals(listOf("u1", "u2"), books.map { it.path })
        assertEquals(listOf(100L, 200L), books.map { it.sizeBytes })
    }

    @Test fun `junk and hidden entries are skipped, exactly as a filesystem scan skips them`() {
        val books = SafScanner.scan(
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
        val books = SafScanner.scan(
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
        val books = SafScanner.scan(
            dir("root"),
            tree("root" to listOf(dir("pages")), "pages" to listOf(file("p1", "001.jpg"))),
        )
        assertTrue(books.isEmpty())
    }

    @Test fun `a tree that contains itself ends instead of recursing forever`() {
        val books = SafScanner.scan(
            dir("root"),
            tree("root" to listOf(dir("root"), file("u1", "Batman.cbz"))),
        )
        assertEquals(listOf("u1"), books.map { it.path })
    }
}
