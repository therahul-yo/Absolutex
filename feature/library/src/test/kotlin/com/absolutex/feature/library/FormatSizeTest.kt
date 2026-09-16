package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSizeTest {

    @Test fun `bytes below a kilobyte keep their unit`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("999 B", formatSize(999))
    }

    @Test fun `kilobytes get one decimal`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
    }

    @Test fun `ten and above drops the decimal`() {
        assertEquals("10 KB", formatSize(10 * 1024))
        assertEquals("512 KB", formatSize(512 * 1024))
    }

    @Test fun `it climbs through the units`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
        assertEquals("1.0 TB", formatSize(1024L * 1024 * 1024 * 1024))
    }

    @Test fun `it stops at terabytes rather than inventing a unit`() {
        assertEquals("1024 TB", formatSize(1024L * 1024 * 1024 * 1024 * 1024))
    }

    @Test fun `a negative size is not rendered as a number`() {
        // LibraryBook.sizeBytes comes off disk; a stat failure must not print "-1 B".
        assertEquals("—", formatSize(-1))
    }
}
