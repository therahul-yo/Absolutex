package com.absolutex.core.data.settings

import com.absolutex.core.data.BookPrefs
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class BookOverrideTest {

    private val global = ReaderPrefs(readingFlow = ReadingFlow.LTR, pageLayout = PageLayout.SINGLE)

    @Test fun `no row means the global settings, untouched`() {
        assertEquals(global, global.overriddenBy(null))
        assertEquals(global, global.overriddenBy(BookPrefs("book")))
    }

    @Test fun `a book reads the way it says it does`() {
        val manga = BookPrefs("book", readingFlow = "RTL", pageLayout = "DOUBLE_WITH_COVER")
        val prefs = global.overriddenBy(manga)
        assertEquals(ReadingFlow.RTL, prefs.readingFlow)
        assertEquals(PageLayout.DOUBLE_WITH_COVER, prefs.pageLayout)
    }

    @Test fun `overriding one field leaves the others global`() {
        val prefs = global.copy(pageLayout = PageLayout.DOUBLE)
            .overriddenBy(BookPrefs("book", readingFlow = "VERTICAL"))
        assertEquals(ReadingFlow.VERTICAL, prefs.readingFlow)
        assertEquals(PageLayout.DOUBLE, prefs.pageLayout)
    }

    @Test fun `a value this build does not know keeps the global one`() {
        // A row written by a later version, or a hand-edited database, must not reset the reader.
        val prefs = global.overriddenBy(BookPrefs("book", readingFlow = "DIAGONAL", pageLayout = "SPIRAL"))
        assertEquals(global, prefs)
    }
}
