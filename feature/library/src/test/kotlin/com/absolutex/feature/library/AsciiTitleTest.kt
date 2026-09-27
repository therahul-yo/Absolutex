package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The section titles' character art: shape, shadow, and the fallback for unknown letters. */
class AsciiTitleTest {

    @Test fun `every section title can be drawn`() {
        for (title in listOf("Comics", "Recent", "Favourites", "Documents")) {
            val rows = asciiBanner(title)
            assertTrue(title, rows != null && rows.size == 7)
        }
    }

    @Test fun `a letter is its 5x7 glyph, lit pixels as blocks`() {
        assertEquals(
            listOf("█████", "  █", "  █", "  █", "  █", "  █", "  █"),
            asciiBanner("T"),
        )
    }

    @Test fun `letters are one column apart`() {
        assertEquals("█   █ █████", asciiBanner("HI")!!.first())
    }

    @Test fun `lower case draws as capitals`() {
        assertEquals(asciiBanner("COMICS"), asciiBanner("comics"))
    }

    @Test fun `a letter the font lacks falls back to plain text`() {
        assertNull(asciiBanner("Récents"))
        assertNull(asciiBanner("漫画"))
    }
}
