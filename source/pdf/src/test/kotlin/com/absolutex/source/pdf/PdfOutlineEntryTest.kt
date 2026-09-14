package com.absolutex.source.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOutlineEntryTest {

    @Test fun `an entry pointing at a page is resolved`() {
        assertTrue(PdfOutlineEntry("Chapter 1", depth = 0, pageIndex = 0).isResolved)
    }

    // A bookmark targeting an external file or a URI has no page in this document; the
    // reader still shows the row, it just cannot navigate to it.
    @Test fun `an entry with no destination in this document is unresolved`() {
        val entry = PdfOutlineEntry("See other file", 0, PdfOutlineEntry.UNRESOLVED)
        assertFalse(entry.isResolved)
    }
}
