package com.absolutex.feature.library

import com.absolutex.core.data.LibraryBook
import com.absolutex.model.BookIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §5.1 deduplication, which was missing entirely: the same file under two configured locations
 * showed twice, doubled a series shelf, and gave one book two rows sharing one reading position.
 */
class LibraryDedupTest {

    private fun row(path: String, size: Long) = LibraryBook(
        path = path,
        // Built the way the scanner builds it, so the fixture cannot drift from the real key.
        contentKey = BookIdentity.of(path.substringAfterLast('/'), size),
        series = "Batman",
        title = null,
        issue = 1.0,
        issueRaw = "1",
        volume = null,
        year = null,
        sizeBytes = size,
        lastModified = 0,
        isImageFolder = false,
        pageCount = null,
        addedAt = 0,
        seenAtScan = 1,
    )

    @Test fun `one file through two locations is one book`() {
        val books = listOf(
            row("/sdcard/Comics/Batman 001.cbz", size = 900),
            row("/sdcard/Backup/Batman 001.cbz", size = 900),
        )
        val deduped = books.deduplicatedByIdentity()
        assertEquals(1, deduped.size)
        // First occurrence wins, so the order the locations were configured in decides the path.
        assertEquals("/sdcard/Comics/Batman 001.cbz", deduped.single().path)
    }

    @Test fun `same name and different size stays two books`() {
        // Identity is name and size: a genuinely different file must not be merged away.
        val books = listOf(
            row("/a/Batman 001.cbz", size = 900),
            row("/b/Batman 001.cbz", size = 901),
        )
        assertEquals(2, books.deduplicatedByIdentity().size)
    }

    @Test fun `different books under one location are untouched`() {
        val books = listOf(row("/a/Batman 001.cbz", 1), row("/a/Batman 002.cbz", 2))
        assertEquals(books, books.deduplicatedByIdentity())
    }

    @Test fun `the order of the rest of the list survives`() {
        val books = listOf(
            row("/a/Issue 3.cbz", 3),
            row("/b/Issue 3.cbz", 3),
            row("/a/Issue 1.cbz", 1),
        )
        assertEquals(
            listOf("/a/Issue 3.cbz", "/a/Issue 1.cbz"),
            books.deduplicatedByIdentity().map { it.path },
        )
    }
}
