package com.absolutex.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextBookTest {

    private fun book(
        path: String,
        series: String? = "Batman",
        issue: Double? = null,
    ) = LibraryBook(
        path = path,
        contentKey = "${path.substringAfterLast('/')}:1",
        series = series,
        title = null,
        issue = issue,
        issueRaw = issue?.toString(),
        volume = null,
        year = null,
        sizeBytes = 1,
        lastModified = 0,
        isImageFolder = false,
        pageCount = null,
        addedAt = 0,
        seenAtScan = 0,
    )

    @Test
    fun `the next book in the series is the one with the next issue number`() {
        val one = book("/lib/Batman 001.cbz", issue = 1.0)
        val two = book("/lib/Batman 002.cbz", issue = 2.0)
        val three = book("/lib/Batman 003.cbz", issue = 3.0)
        val next = NextBook.after(
            one, listOf(one, two, three), NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER,
        )
        assertEquals(two, next)
    }

    @Test
    fun `a tie on issue number is broken by filename`() {
        val a = book("/lib/Batman 001a.cbz", issue = 1.0)
        val b = book("/lib/Batman 001b.cbz", issue = 1.0)
        val next = NextBook.after(a, listOf(b, a), NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER)
        assertEquals(b, next)
    }

    @Test
    fun `books with no issue number sort after the numbered ones, by filename`() {
        val one = book("/lib/Batman 001.cbz", issue = 1.0)
        val extraA = book("/lib/Batman Annual a.cbz", issue = null)
        val extraB = book("/lib/Batman Annual b.cbz", issue = null)
        val candidates = listOf(extraB, one, extraA)
        assertEquals(
            extraA,
            NextBook.after(one, candidates, NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER),
        )
        assertEquals(
            extraB,
            NextBook.after(extraA, candidates, NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER),
        )
    }

    @Test
    fun `raw filename order ignores the parsed issue number`() {
        // Issue numbers are deliberately out of filename order, so a filename-order result proves
        // PARSED_NUMBER's ordering was not used.
        val ten = book("/lib/Batman 010.cbz", issue = 1.0)
        val two = book("/lib/Batman 002.cbz", issue = 99.0)
        val next = NextBook.after(two, listOf(ten, two), NextBookScope.WHOLE_LIBRARY, NextBookOrder.RAW_FILENAME)
        assertEquals(ten, next)
    }

    @Test
    fun `current folder scope excludes books from another folder`() {
        val inFolder = book("/lib/one/Batman 001.cbz", issue = 1.0)
        val alsoInFolder = book("/lib/one/Batman 002.cbz", issue = 2.0)
        // Would win by issue number if the folder scope did not exclude it first.
        val otherFolder = book("/lib/two/Batman 000.cbz", issue = 1.5)
        val next = NextBook.after(
            inFolder,
            listOf(inFolder, alsoInFolder, otherFolder),
            NextBookScope.CURRENT_FOLDER,
            NextBookOrder.PARSED_NUMBER,
        )
        assertEquals(alsoInFolder, next)
    }

    @Test
    fun `the last book in scope has no next book`() {
        val one = book("/lib/Batman 001.cbz", issue = 1.0)
        val two = book("/lib/Batman 002.cbz", issue = 2.0)
        assertNull(NextBook.after(two, listOf(one, two), NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER))
    }

    @Test
    fun `a current book missing from the candidates has no next book`() {
        val one = book("/lib/Batman 001.cbz", issue = 1.0)
        val ghost = book("/lib/Batman 999.cbz", issue = 999.0)
        assertNull(NextBook.after(ghost, listOf(one), NextBookScope.WHOLE_LIBRARY, NextBookOrder.PARSED_NUMBER))
    }
}
