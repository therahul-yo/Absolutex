package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TocTest {

    @Test fun `a folder per issue becomes one entry per issue`() {
        val entries = Toc.fromEntryNames(
            listOf("Issue 1/001.jpg", "Issue 1/002.jpg", "Issue 2/001.jpg", "Issue 2/002.jpg"),
        )
        assertEquals(listOf(TocEntry("Issue 1", 0), TocEntry("Issue 2", 2)), entries)
    }

    @Test fun `a flat archive has no contents`() {
        assertEquals(emptyList<TocEntry>(), Toc.fromEntryNames(listOf("001.jpg", "002.jpg")))
    }

    @Test fun `one wrapper folder is not contents`() {
        assertEquals(emptyList<TocEntry>(), Toc.fromEntryNames(listOf("Batman/001.jpg", "Batman/002.jpg")))
    }

    @Test fun `nested folders carry their depth and their own name`() {
        val entries = Toc.fromEntryNames(
            listOf("Vol 1/Issue 1/001.jpg", "Vol 1/Issue 2/001.jpg", "Vol 2/Issue 1/001.jpg"),
        )
        assertEquals(
            listOf(TocEntry("Issue 1", 0, 1), TocEntry("Issue 2", 1, 1), TocEntry("Issue 1", 2, 1)),
            entries,
        )
    }

    @Test fun `a folder the reader returns to is listed again, at the page it resumes`() {
        // Entry order is the reading order: a folder that reappears is a second run of pages.
        val entries = Toc.fromEntryNames(listOf("A/1.jpg", "B/1.jpg", "A/2.jpg"))
        assertEquals(listOf(TocEntry("A", 0), TocEntry("B", 1), TocEntry("A", 2)), entries)
    }

    @Test fun `an empty book has no contents`() {
        assertEquals(emptyList<TocEntry>(), Toc.fromEntryNames(emptyList()))
    }
}
