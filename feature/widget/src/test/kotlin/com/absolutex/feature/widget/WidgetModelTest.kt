package com.absolutex.feature.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetModelTest {

    @Test fun `first page yields one over pageCount`() {
        assertEquals(0.1f, WidgetModel.fractionFor(0, 10), 0.0001f)
    }

    @Test fun `opening page of a single-page book is complete`() {
        assertEquals(1f, WidgetModel.fractionFor(0, 1), 0.0001f)
    }

    @Test fun `last page yields exactly one`() {
        assertEquals(1f, WidgetModel.fractionFor(44, 45), 0.0001f)
    }

    @Test fun `over-read index clamps to one`() {
        assertEquals(1f, WidgetModel.fractionFor(99, 45), 0.0001f)
    }

    @Test fun `negative index clamps to zero`() {
        assertEquals(0f, WidgetModel.fractionFor(-2, 10), 0.0001f)
    }

    @Test fun `zero pageCount yields zero instead of NaN`() {
        assertEquals(0f, WidgetModel.fractionFor(0, 0), 0.0001f)
    }

    @Test fun `negative pageCount yields zero instead of NaN`() {
        assertEquals(0f, WidgetModel.fractionFor(3, -5), 0.0001f)
    }

    @Test fun `from wires every field and derives the fraction`() {
        val model = WidgetModel.from("Batman 001.cbz:100", "The Long Halloween", 12, 45, null)
        assertEquals("Batman 001.cbz:100", model.bookId)
        assertEquals("The Long Halloween", model.title)
        assertEquals(12, model.pageIndex)
        assertEquals(45, model.pageCount)
        assertEquals(13f / 45f, model.progressFraction, 0.0001f)
        assertNull(model.coverKey)
    }

    @Test fun `from keeps the cover key for later thumbnail wiring`() {
        val model = WidgetModel.from("id", "Title", 0, 10, "thumb-key")
        assertEquals("thumb-key", model.coverKey)
    }

    @Test fun `display title prefers the library title`() {
        assertEquals("The Long Halloween", displayTitle("The Long Halloween", "Batman", "/sd/a.cbz"))
    }

    @Test fun `display title falls back to series then filename`() {
        assertEquals("Batman", displayTitle(null, "Batman", "/sd/Batman 001.cbz"))
        assertEquals("Batman 001.cbz", displayTitle(null, null, "/sd/Comics/Batman 001.cbz"))
    }
}
