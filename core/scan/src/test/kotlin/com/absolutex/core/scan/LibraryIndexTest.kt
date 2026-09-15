package com.absolutex.core.scan

import com.absolutex.source.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {

    private fun book(path: String, size: Long = 100) =
        ScannedBook(path, size, FilenameParser.parse(path))

    // ---- deduplication across locations (§5.1) ----

    @Test fun `the same file seen through two locations appears once`() {
        val books = listOf(
            book("/storage/sd/Comics/Batman 001.cbz", 5_000),
            book("/storage/emulated/0/Comics/Batman 001.cbz", 5_000),
        )
        val deduped = LibraryIndex.deduplicate(books)
        assertEquals(1, deduped.size)
        // First configured location wins, so the shown path is predictable.
        assertEquals("/storage/sd/Comics/Batman 001.cbz", deduped.single().path)
    }

    @Test fun `same name different size is not a duplicate`() {
        val books = listOf(
            book("/a/Batman 001.cbz", 5_000),
            book("/b/Batman 001.cbz", 9_999),
        )
        assertEquals(2, LibraryIndex.deduplicate(books).size)
    }

    @Test fun `different issues of one series are never deduplicated`() {
        val books = (1..20).map { book("/a/Batman %03d.cbz".format(it), it * 1000L) }
        assertEquals(20, LibraryIndex.deduplicate(books).size)
    }

    // ---- shelves ----

    @Test fun `books group onto one shelf per series`() {
        val shelves = LibraryIndex.groupIntoSeries(
            listOf(
                book("/c/Batman 001.cbz"),
                book("/c/Batman 002.cbz"),
                book("/c/Superman 001.cbz"),
            ),
        )
        assertEquals(listOf("Batman", "Superman"), shelves.map { it.name })
        assertEquals(2, shelves.first().size)
    }

    @Test fun `a book with no parsed series falls back to its folder, not one unnamed shelf`() {
        val shelves = LibraryIndex.groupIntoSeries(
            listOf(book("/Comics/Weird Stuff/1234.cbz"), book("/Comics/Other Stuff/5678.cbz")),
        )
        assertEquals(listOf("Other Stuff", "Weird Stuff"), shelves.map { it.name })
    }

    @Test fun `shelf totals add up`() {
        val shelf = LibraryIndex.groupIntoSeries(
            listOf(book("/c/Batman 001.cbz", 10), book("/c/Batman 002.cbz", 32)),
        ).single()
        assertEquals(42L, shelf.totalBytes)
    }

    // ---- sorting ----

    @Test fun `name sort is natural, so issue 2 precedes issue 10`() {
        val sorted = LibraryIndex.sort(
            listOf(book("/c/Batman 010.cbz"), book("/c/Batman 002.cbz"), book("/c/Batman 001.cbz")),
            SortKey.NAME,
        ).map { it.path.substringAfterLast('/') }
        assertEquals(listOf("Batman 001.cbz", "Batman 002.cbz", "Batman 010.cbz"), sorted)
    }

    @Test fun `every sort key reverses`() {
        val books = listOf(book("/c/a.cbz", 3), book("/c/b.cbz", 1), book("/c/c.cbz", 2))
        for (key in SortKey.entries) {
            val up = LibraryIndex.sort(books, key, ascending = true)
            val down = LibraryIndex.sort(books, key, ascending = false)
            assertEquals("$key did not reverse", up, down.reversed())
        }
    }

    @Test fun `size sort orders by bytes`() {
        val sorted = LibraryIndex.sort(
            listOf(book("/c/big.cbz", 900), book("/c/small.cbz", 10)),
            SortKey.SIZE,
        )
        assertEquals(10L, sorted.first().sizeBytes)
    }

    // ---- search ----

    @Test fun `search matches series, title and filename, case-insensitively`() {
        val books = listOf(
            book("/c/Absolute Batman 001 (2024).cbr"),
            book("/c/Superman 001.cbz"),
        )
        assertEquals(1, LibraryIndex.search(books, "batman").size)
        assertEquals(1, LibraryIndex.search(books, "SUPER").size)
        assertEquals(2, LibraryIndex.search(books, "001").size)
    }

    @Test fun `an empty query returns everything`() {
        val books = listOf(book("/c/a.cbz"), book("/c/b.cbz"))
        assertEquals(2, LibraryIndex.search(books, "   ").size)
    }

    @Test fun `search stays instant on a large library`() {
        // §5.1: instant-as-you-type on 5,000+ items. A keystroke has ~8 ms at 120 Hz; this is the
        // whole-list scan, so budget it well inside a frame.
        // "#" makes the issue explicit. Without it a name like "Series 200 2000" is genuinely
        // ambiguous — the parser reads 2000 as a year and 200 as the issue, which is the right
        // reading of that name and the wrong data for this test.
        val books = (1..5_000).map { book("/c/Series ${it % 300} #${"%04d".format(it)}.cbz") }
        val started = System.nanoTime()
        repeat(10) { LibraryIndex.search(books, "series 12") }
        val perQueryMs = (System.nanoTime() - started) / 10 / 1_000_000.0
        println("SEARCH_BUDGET 5000 items, %.2f ms/query".format(perQueryMs))
        assertTrue("search took %.2f ms/query on 5000 items".format(perQueryMs), perQueryMs < 8.0)
    }

    @Test fun `grouping stays cheap on a large library`() {
        val books = (1..5_000).map { book("/c/Series ${it % 300} #${"%04d".format(it)}.cbz") }
        val started = System.nanoTime()
        val shelves = LibraryIndex.groupIntoSeries(books)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("GROUP_BUDGET 5000 items into ${shelves.size} shelves in ${ms}ms")
        assertEquals(300, shelves.size)
        assertTrue("grouping took ${ms}ms", ms < 1_000)
    }

    @Test fun `a year-shaped second number is read as a year, not kept in the series`() {
        // Recorded because it surprised a perf test: "Series 200 2000" is ambiguous, and this is
        // the reading the parser commits to. "Batman 200 2000.cbz" is issue 200 of Batman, 2000.
        val shelves = LibraryIndex.groupIntoSeries(listOf(book("/c/Batman 200 2000.cbz")))
        assertEquals(listOf("Batman"), shelves.map { it.name })
        assertEquals(2000, shelves.single().books.single().parsed.year)
        assertEquals(200.0, shelves.single().books.single().parsed.issue!!.value, 0.0)
    }
}
