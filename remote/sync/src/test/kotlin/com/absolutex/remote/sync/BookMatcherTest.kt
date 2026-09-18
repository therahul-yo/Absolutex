package com.absolutex.remote.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric provides the real org.json on the JVM (see KomgaClientTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookMatcherTest {

    private fun komgaBook(name: String, size: Long?) = BookRef(
        id = "k-$name-$size",
        name = name,
        seriesId = "s",
        pageCount = 24,
        sizeBytes = size,
    )

    private fun kavitaFile(name: String, bytes: Long) = KavitaFileRef(fileName = name, bytes = bytes, pages = null)

    private fun kavitaChapter(id: Int, vararg files: KavitaFileRef) = KavitaChapterFiles(
        chapterId = id,
        volumeId = 1,
        seriesId = 7,
        files = files.toList(),
    )

    @Test fun `komga exact name and size matches`() {
        val books = listOf(
            komgaBook("Other.cbz", 10L),
            komgaBook("Batman 001.cbz", 2000L),
        )
        assertEquals(books[1], matchKomgaBook("Batman 001.cbz:2000", books))
    }

    @Test fun `komga same name different size does not match`() {
        val books = listOf(komgaBook("Batman 001.cbz", 1999L))
        assertNull(matchKomgaBook("Batman 001.cbz:2000", books))
    }

    @Test fun `komga book without a size is left alone`() {
        val books = listOf(komgaBook("Batman 001.cbz", null))
        assertNull(matchKomgaBook("Batman 001.cbz:2000", books))
    }

    @Test fun `komga duplicate match is left alone, never guessed`() {
        val books = listOf(
            komgaBook("Batman 001.cbz", 2000L),
            komgaBook("Batman 001.cbz", 2000L),
        )
        assertNull(matchKomgaBook("Batman 001.cbz:2000", books))
    }

    @Test fun `komga match is case-sensitive and exact`() {
        val books = listOf(komgaBook("batman 001.cbz", 2000L))
        assertNull(matchKomgaBook("Batman 001.cbz:2000", books))
    }

    @Test fun `kavita windows path matches on base name and bytes`() {
        val chapters = listOf(
            kavitaChapter(9, kavitaFile("Other.cbz", 10L)),
            kavitaChapter(10, kavitaFile("Batman 001.cbz", 2000L)),
        )
        assertEquals(10, matchKavitaFile("Batman 001.cbz:2000", chapters)?.first?.chapterId)
        assertNull(matchKavitaFile("Batman 001.cbz:2000", chapters.take(1)))
    }

    @Test fun `kavita volume parsing strips server paths to base names`() {
        val body = "{\"id\":3,\"seriesId\":7,\"chapters\":[" +
            "{\"id\":9,\"volumeId\":3,\"files\":[" +
            "{\"id\":1,\"filePath\":\"C:\\\\Comics\\\\Batman 001.cbz\",\"pages\":24,\"bytes\":2000}," +
            "{\"id\":2,\"filePath\":\"/comics/other.cbz\",\"pages\":20}" +
            "]}]}"
        val chapters = parseKavitaVolume(body)
        assertEquals(1, chapters.size)
        assertEquals(9, chapters[0].chapterId)
        assertEquals(3, chapters[0].volumeId)
        assertEquals(7, chapters[0].seriesId)
        assertEquals(
            listOf(KavitaFileRef("Batman 001.cbz", 2000L, 24)),
            chapters[0].files,
        )
    }

    @Test fun `kavita duplicate file match is left alone`() {
        val chapters = listOf(
            kavitaChapter(9, kavitaFile("Batman 001.cbz", 2000L)),
            kavitaChapter(10, kavitaFile("Batman 001.cbz", 2000L)),
        )
        assertNull(matchKavitaFile("Batman 001.cbz:2000", chapters))
    }

    @Test fun `komga page base is one-based both ways`() {
        assertEquals(0, komgaPageToIndex(1))
        assertEquals(5, komgaPageToIndex(6))
        assertEquals(0, komgaPageToIndex(0))
        assertEquals(1, komgaIndexToPage(0))
        assertEquals(6, komgaIndexToPage(5))
    }

    @Test fun `kavita page base is zero-based and floored`() {
        assertEquals(0, kavitaPageToIndex(0))
        assertEquals(4, kavitaPageToIndex(4))
        assertEquals(0, kavitaPageToIndex(-3))
    }
}
