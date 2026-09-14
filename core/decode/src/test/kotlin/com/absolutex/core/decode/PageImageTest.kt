package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageImageTest {

    @Test fun `real page on the reference device binds on width, not height`() {
        // Absolute Batman 001 is 1988x3057 (aspect 0.650); the OnePlus 11R viewport is
        // 1240x2772 (aspect 0.447). The page is relatively wider, so fit-screen binds on
        // WIDTH and letterboxes 866px vertically. This is the case that makes fit-width a
        // genuinely different mode from fit-screen on this hardware, not a cosmetic option.
        val (w, h) = PageImage.fitInside(1988, 3057, 1240, 2772)
        assertEquals(1240, w)
        assertEquals(1906, h)
        assertTrue("expected vertical letterboxing", h < 2772)
    }

    @Test fun `never upscales at decode time`() {
        assertEquals(400 to 300, PageImage.fitInside(400, 300, 4000, 3000))
    }

    @Test fun `fits a wide spread by width`() {
        val (w, h) = PageImage.fitInside(4000, 1000, 1240, 2772)
        assertEquals(1240, w)
        assertEquals(310, h)
    }

    @Test fun `degenerate sizes pass through instead of dividing by zero`() {
        assertEquals(0 to 0, PageImage.fitInside(0, 0, 100, 100))
        assertEquals(100 to 100, PageImage.fitInside(100, 100, 0, 0))
    }

    @Test fun `never returns a zero dimension for an extreme aspect ratio`() {
        val (w, h) = PageImage.fitInside(12000, 3, 1240, 2772)
        assertEquals(1240, w)
        assertEquals(1, h)   // clamped to 1, not 0 - a 0-dimension bitmap throws
    }
}
