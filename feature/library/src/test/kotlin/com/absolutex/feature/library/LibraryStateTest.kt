package com.absolutex.feature.library

import com.absolutex.core.scan.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field of [LibraryBookUi] is required, so tests spell out only what they care about and
 * take the rest from here. A default on the production type would hide exactly the bug this
 * codebase already paid for once (`TileKey.bookId` defaulting to `""`).
 */
private fun book(
    path: String = "/books/Series 01.cbz",
    displayName: String = "Series #1",
    originalFilename: String = path.substringAfterLast('/'),
    series: String? = "Series",
    sizeBytes: Long = 1_000L,
    lastModified: Long = 1_000L,
    addedAt: Long = 1_000L,
    pageCount: Int? = 20,
    currentPage: Int? = null,
    isFavorite: Boolean = false,
) = LibraryBookUi(
    path = path,
    displayName = displayName,
    originalFilename = originalFilename,
    series = series,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    addedAt = addedAt,
    pageCount = pageCount,
    currentPage = currentPage,
    isFavorite = isFavorite,
)

private fun state(
    books: List<LibraryBookUi>,
    section: HomeSection = HomeSection.SERIES,
    query: String = "",
    sort: SortSpec = SortSpec(SortKey.NAME, ascending = true),
    hasLocations: Boolean = true,
    loading: Boolean = false,
) = LibraryUiState.Initial.copy(
    loading = loading,
    allBooks = books,
    section = section,
    query = query,
    sort = sort,
    hasLocations = hasLocations,
)

class ReadStateTest {

    @Test fun `never opened is unread`() {
        assertEquals(ReadState.UNREAD, book(currentPage = null).readState)
    }

    @Test fun `opened but still on page zero is unread`() {
        // Opening a book and immediately backing out must not move it out of Unread.
        assertEquals(ReadState.UNREAD, book(currentPage = 0).readState)
    }

    @Test fun `mid-book is in progress`() {
        assertEquals(ReadState.IN_PROGRESS, book(currentPage = 5, pageCount = 20).readState)
    }

    @Test fun `last page is finished`() {
        assertEquals(ReadState.FINISHED, book(currentPage = 19, pageCount = 20).readState)
    }

    @Test fun `past the last page is still finished, not in progress`() {
        // A stale progress row from before a rescan shortened the book must not read as ongoing.
        assertEquals(ReadState.FINISHED, book(currentPage = 99, pageCount = 20).readState)
    }

    @Test fun `unknown page count with a position is in progress, not finished`() {
        assertEquals(ReadState.IN_PROGRESS, book(currentPage = 3, pageCount = null).readState)
    }

    @Test fun `progress fraction is null without both numbers`() {
        assertNull(book(currentPage = null, pageCount = 20).progressFraction)
        assertNull(book(currentPage = 3, pageCount = null).progressFraction)
    }

    @Test fun `progress fraction is null for a single-page book`() {
        // 1/1 would render a full bar on a book that was merely opened.
        assertNull(book(currentPage = 0, pageCount = 1).progressFraction)
    }

    @Test fun `progress fraction counts the current page as read`() {
        assertEquals(0.5f, book(currentPage = 9, pageCount = 20).progressFraction!!, 1e-6f)
    }

    @Test fun `progress fraction is clamped`() {
        assertEquals(1f, book(currentPage = 99, pageCount = 20).progressFraction!!, 1e-6f)
    }
}

class SortTest {

    @Test fun `name sort is natural, not lexicographic`() {
        val books = listOf(
            book(path = "/b/Issue 10.cbz"),
            book(path = "/b/Issue 2.cbz"),
            book(path = "/b/Issue 1.cbz"),
        )
        val names = books.sortedBy(SortSpec(SortKey.NAME, ascending = true)).map { it.originalFilename }
        assertEquals(listOf("Issue 1.cbz", "Issue 2.cbz", "Issue 10.cbz"), names)
    }

    @Test fun `name sort descending reverses`() {
        val books = listOf(book(path = "/b/a.cbz"), book(path = "/b/b.cbz"))
        val names = books.sortedBy(SortSpec(SortKey.NAME, ascending = false)).map { it.originalFilename }
        assertEquals(listOf("b.cbz", "a.cbz"), names)
    }

    @Test fun `size sort orders by bytes both ways`() {
        val books = listOf(
            book(path = "/b/big.cbz", sizeBytes = 900),
            book(path = "/b/small.cbz", sizeBytes = 10),
        )
        assertEquals(
            listOf(10L, 900L),
            books.sortedBy(SortSpec(SortKey.SIZE, ascending = true)).map { it.sizeBytes },
        )
        assertEquals(
            listOf(900L, 10L),
            books.sortedBy(SortSpec(SortKey.SIZE, ascending = false)).map { it.sizeBytes },
        )
    }

    @Test fun `date sort uses the persisted timestamp, not the filesystem`() {
        // These paths do not exist. LibraryIndex.sort would call File.lastModified() and get 0
        // for both; reading the scanned column is what makes this orderable at all.
        val books = listOf(
            book(path = "/nonexistent/late.cbz", lastModified = 2_000),
            book(path = "/nonexistent/early.cbz", lastModified = 1_000),
        )
        assertEquals(
            listOf("early.cbz", "late.cbz"),
            books.sortedBy(SortSpec(SortKey.DATE, ascending = true)).map { it.originalFilename },
        )
    }
}

class SortSelectionTest {

    @Test fun `picking a new field sorts ascending`() {
        val start = SortSpec(SortKey.NAME, ascending = false)
        assertEquals(SortSpec(SortKey.SIZE, ascending = true), start.select(SortKey.SIZE))
    }

    @Test fun `picking the active field reverses it`() {
        val start = SortSpec(SortKey.NAME, ascending = true)
        val flipped = start.select(SortKey.NAME)
        assertEquals(SortSpec(SortKey.NAME, ascending = false), flipped)
        assertEquals(start, flipped.select(SortKey.NAME))
    }
}
