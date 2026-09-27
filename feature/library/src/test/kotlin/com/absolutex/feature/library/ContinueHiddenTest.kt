package com.absolutex.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taking a book off Continue reading hides it until it is read again, and no longer. */
class ContinueHiddenTest {

    private fun book(lastReadAt: Long?) = LibraryBookUi(
        path = "/comics/a.cbz", displayName = "A", originalFilename = "a.cbz", series = null,
        sizeBytes = 1, lastModified = 0, addedAt = 0, pageCount = 20, currentPage = 5,
        isFavorite = false, lastReadAt = lastReadAt,
    )

    @Test fun `a book never hidden is shown`() {
        assertTrue(isShown(book(lastReadAt = 100), emptyMap()))
    }

    @Test fun `a hidden book stays hidden while unread since`() {
        assertFalse(isShown(book(lastReadAt = 100), mapOf("/comics/a.cbz" to 200L)))
    }

    @Test fun `reading it again after hiding brings it back`() {
        assertTrue(isShown(book(lastReadAt = 300), mapOf("/comics/a.cbz" to 200L)))
    }

    @Test fun `hiding one book leaves the others alone`() {
        assertTrue(isShown(book(lastReadAt = 100), mapOf("/comics/other.cbz" to 200L)))
    }
}
