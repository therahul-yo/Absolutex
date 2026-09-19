package com.absolutex.feature.reader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-colour-per-settled-page selection (§4, §5.2, milestone 5): pure, so this runs on the
 * plain JVM, no Robolectric or instrumentation harness needed.
 */
class ReaderBackgroundTest {

    private val red = Color(0xFFFF0000.toInt())
    private val blue = Color(0xFF0000FF.toInt())

    @Test
    fun `the settled page's own colour is picked`() {
        val backgrounds = mapOf(0 to red, 1 to blue)
        assertEquals(red, readerBackgroundFor(autoBackground = true, pageBackgrounds = backgrounds, settledPage = 0))
        assertEquals(blue, readerBackgroundFor(autoBackground = true, pageBackgrounds = backgrounds, settledPage = 1))
    }

    @Test
    fun `a page with no report yet falls back to black, not a neighbour's colour`() {
        val backgrounds = mapOf(0 to red)
        assertEquals(
            Color.Black,
            readerBackgroundFor(autoBackground = true, pageBackgrounds = backgrounds, settledPage = 1),
        )
    }

    @Test
    fun `the toggle off is steady black regardless of what has been sampled`() {
        val backgrounds = mapOf(0 to red, 1 to blue)
        assertEquals(
            Color.Black,
            readerBackgroundFor(autoBackground = false, pageBackgrounds = backgrounds, settledPage = 1),
        )
    }

    @Test
    fun `an empty map before any page settles is black`() {
        assertEquals(
            Color.Black,
            readerBackgroundFor(autoBackground = true, pageBackgrounds = emptyMap(), settledPage = 0),
        )
    }
}
