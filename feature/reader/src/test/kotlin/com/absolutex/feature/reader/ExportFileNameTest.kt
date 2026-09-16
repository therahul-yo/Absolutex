package com.absolutex.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {

    @Test fun `pages are numbered from one, not zero`() {
        assertEquals("Batman p1.jpg", exportFileName("Batman", 0, "jpg"))
        assertEquals("Batman p45.jpg", exportFileName("Batman", 44, "jpg"))
    }

    @Test fun `characters a file system would reject are replaced`() {
        assertEquals("Batman_ Year One _ v2 p2.png", exportFileName("Batman: Year One / v2", 1, "png"))
    }

    @Test fun `the book's own extension is not carried into the page's name`() {
        assertEquals("absolute-batman-001 p17.jpg", exportFileName("absolute-batman-001.cbr", 16, "jpg"))
        assertEquals("Batman v2 p1.png", exportFileName("Batman v2.cbz", 0, "png"))
        // Not an extension: a title that merely ends in a dot-something too long to be one.
        assertEquals("Batman.chapter one p1.jpg", exportFileName("Batman.chapter one", 0, "jpg"))
    }

    @Test fun `a book with no usable title still exports`() {
        assertEquals("page p1.jpg", exportFileName("", 0, "jpg"))
        assertEquals("page p1.jpg", exportFileName("///", 0, "jpg"))
    }
}
