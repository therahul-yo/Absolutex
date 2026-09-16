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

    @Test fun `a book with no usable title still exports`() {
        assertEquals("page p1.jpg", exportFileName("", 0, "jpg"))
        assertEquals("page p1.jpg", exportFileName("///", 0, "jpg"))
    }
}
