package com.absolutex.source.folder

import com.absolutex.source.EntryFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * §2: a folder of loose images is one book. What can go wrong here is the page *set* and the page
 * *order*, so that is what these pin — plus the two failures a folder has and an archive does not:
 * a file that disappears mid-read, and pages that arrive in whatever order storage felt like.
 */
class FolderComicSourceTest {

    @Test fun `pages sort naturally, not lexically`() {
        // The headline case: a lexical sort puts "10" before "2" and the book reads out of order.
        val source = FolderComicSource.open(entries("10.jpg", "2.jpg", "1.jpg", "20.jpg", "3.jpg"))
        assertEquals(listOf("1.jpg", "2.jpg", "3.jpg", "10.jpg", "20.jpg"), source.pages.map { it.entryName })
    }

    @Test fun `zero padding and mixed padding stay deterministic`() {
        val source = FolderComicSource.open(entries("page007.jpg", "page10.jpg", "page8.jpg", "page09.jpg"))
        assertEquals(
            listOf("page007.jpg", "page8.jpg", "page09.jpg", "page10.jpg"),
            source.pages.map { it.entryName },
        )
    }

    @Test fun `page indices are dense and start at zero after sorting`() {
        val source = FolderComicSource.open(entries("c.jpg", "a.jpg", "b.jpg"))
        assertEquals(listOf(0, 1, 2), source.pages.map { it.index })
    }

    @Test fun `non-image files are dropped rather than becoming unreadable pages`() {
        // A caller is supposed to filter, but a page that can never decode is worse than a file
        // that was never listed — so the source drops them too.
        val source = FolderComicSource.open(entries("001.jpg", "notes.txt", "002.jpg", "cover.nfo"))
        assertEquals(listOf("001.jpg", "002.jpg"), source.pages.map { it.entryName })
    }

    @Test fun `archiver litter never becomes a page`() {
        val source = FolderComicSource.open(entries("001.jpg", "Thumbs.db", ".DS_Store", "._001.jpg", "002.jpg"))
        assertEquals(listOf("001.jpg", "002.jpg"), source.pages.map { it.entryName })
    }

    @Test fun `an empty folder is an empty book, not a throw`() {
        // The empty-book guard is central (ThumbnailPipeline, the reader); a second one here
        // would only add a differently-worded failure for the same condition.
        val source = FolderComicSource.open(emptyList())
        assertTrue(source.pages.isEmpty())
        assertThrows(IndexOutOfBoundsException::class.java) { source.openPage(0) }
    }

    @Test fun `a folder of only junk is an empty book`() {
        val source = FolderComicSource.open(entries("Thumbs.db", "readme.txt"))
        assertTrue(source.pages.isEmpty())
    }

    // ---- the identity contract -----------------------------------------------------------

    @Test fun `page sizes are carried through, so identity and page list read the same entries`() {
        // BookIdentity.of(name, sizeBytes) for a folder book is the sum of its image pages'
        // sizes, frozen by every row the library has already written. The source must therefore
        // report the same set with the same sizes, or a folder book's progress orphans itself.
        val entries = listOf(
            entry("002.jpg", 250),
            entry("001.jpg", 100),
            entry("notes.txt", 9_999),   // not a page: must not reach the sum
            entry("003.jpg", 75),
        )
        val source = FolderComicSource.open(entries)

        val scannerSum = entries.filter { EntryFilter.isPage(it.name) }.sumOf { it.sizeBytes }
        assertEquals(scannerSum, source.pages.sumOf { it.sizeBytes })
        assertEquals(425L, source.pages.sumOf { it.sizeBytes })
    }

    @Test fun `sizes follow their page through the sort`() {
        val source = FolderComicSource.open(listOf(entry("10.jpg", 10), entry("2.jpg", 2), entry("1.jpg", 1)))
        assertEquals(listOf(1L, 2L, 10L), source.pages.map { it.sizeBytes })
    }

    // ---- reading -------------------------------------------------------------------------

    @Test fun `openPage returns the bytes of the page at that index, not at that listing position`() {
        val source = FolderComicSource.open(
            listOf(payload("10.jpg", "ten"), payload("2.jpg", "two"), payload("1.jpg", "one")),
        )
        assertEquals(listOf("one", "two", "ten"), source.pages.indices.map { source.readPage(it) })
    }

    @Test fun `openCover is the first page in reading order`() {
        val source = FolderComicSource.open(listOf(payload("9.jpg", "nine"), payload("1.jpg", "one")))
        assertEquals("one", String(source.openCover().use { it.readBytes() }))
    }

    @Test fun `a page is opened lazily, once per read`() {
        var opened = 0
        val source = FolderComicSource.open(
            listOf(FolderEntry("001.jpg", 3) { opened++; ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )
        assertEquals("listing a folder must not read its files", 0, opened)
        source.openPage(0).close()
        source.openPage(0).close()
        assertEquals(2, opened)
    }

    @Test fun `a file that vanished between listing and read surfaces as IOException`() {
        // A folder is live storage: the user can delete a page while the book is open. That is
        // ordinary, and it must arrive as the reader's generic IO error, not as a crash.
        val source = FolderComicSource.open(
            listOf(FolderEntry("001.jpg", 10) { throw IOException("no such file") }),
        )
        assertThrows(IOException::class.java) { source.openPage(0) }
    }

    @Test fun `an out of range page is a bounds error naming the book's size`() {
        // The list access would throw IndexOutOfBounds on its own, so the explicit guard exists
        // only for the message — "page 1 of 1" is what makes a logged failure diagnosable, and
        // it is the shape LibArchiveSource and CoreComicSource already use. Asserting it is what
        // keeps the guard from silently rotting into decoration.
        val source = FolderComicSource.open(entries("001.jpg"))
        val over = assertThrows(IndexOutOfBoundsException::class.java) { source.openPage(1) }
        assertEquals("page 1 of 1", over.message)
        val under = assertThrows(IndexOutOfBoundsException::class.java) { source.openPage(-1) }
        assertEquals("page -1 of 1", under.message)
    }

    @Test fun `close leaves the book readable, because it owns no handle`() {
        val source = FolderComicSource.open(listOf(payload("001.jpg", "one")))
        source.close()
        assertEquals("one", source.readPage(0))
    }

    @Test fun `concurrent reads of every page each get their own bytes`() {
        // The ComicSource contract: safe from several threads at once. A folder book has no
        // shared descriptor to corrupt, and this pins that it stays that way.
        val pageCount = 32
        val source = FolderComicSource.open((1..pageCount).map { payload("%03d.jpg".format(it), "page$it") })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = source.pages.indices.map { i -> Callable { source.readPage(i) } }
            val got = pool.invokeAll(work).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1..pageCount).map { "page$it" }, got)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `non-ascii and rtl names order without throwing`() {
        val names = arrayOf("ページ_002.jpg", "ページ_010.jpg", "ページ_001.jpg")
        val source = FolderComicSource.open(entries(*names))
        assertArrayEquals(
            arrayOf("ページ_001.jpg", "ページ_002.jpg", "ページ_010.jpg"),
            source.pages.map { it.entryName }.toTypedArray(),
        )
    }

    // ---- fixtures ----------------------------------------------------------------------

    private fun FolderComicSource.readPage(index: Int): String =
        openPage(index).use { String(it.readBytes()) }

    private fun entries(vararg names: String): List<FolderEntry> = names.map { entry(it, 0) }

    private fun entry(name: String, sizeBytes: Long): FolderEntry =
        FolderEntry(name, sizeBytes) { ByteArrayInputStream(ByteArray(0)) }

    private fun entry(name: String, sizeBytes: Int): FolderEntry = entry(name, sizeBytes.toLong())

    private fun payload(name: String, body: String): FolderEntry {
        val bytes = body.toByteArray()
        return FolderEntry(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) as InputStream }
    }
}
