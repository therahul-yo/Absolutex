package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FitGeometryTest {

    // The reference phone and the reference book: 1240x2772 viewport, 1988x3057 pages.
    private val vw = 1240
    private val vh = 2772
    private val pw = 1988
    private val ph = 3057

    @Test fun `fit screen takes the tighter axis, so the whole page is visible`() {
        val s = FitGeometry.baseScale(FitMode.FIT_SCREEN, vw, vh, pw, ph)
        assertEquals(vw.toFloat() / pw, s, 1e-6f)
        assertEquals(0f, FitGeometry.maxOffsetX(vw, pw, s), 0.5f)
        assertEquals(0f, FitGeometry.maxOffsetY(vh, ph, s), 0.5f)
    }

    @Test fun `fit width on a tall page overflows vertically only, and starts at the top`() {
        val spreadW = 2 * pw // a double-page spread is wider than tall
        val s = FitGeometry.baseScale(FitMode.FIT_WIDTH, vw, vh, pw, 4 * ph)
        assertEquals(0f, FitGeometry.maxOffsetX(vw, pw, s), 0.5f)
        val maxY = FitGeometry.maxOffsetY(vh, 4 * ph, s)
        assertEquals((4 * ph * s - vh) / 2f, maxY, 0.5f)
        // Top edge on the screen's top edge: originY = (vh - drawH) / 2 + offset = 0.
        val drawH = 4 * ph * s
        assertEquals(0f, (vh - drawH) / 2f + FitGeometry.startOffsetY(vh, 4 * ph, s), 0.5f)
        assertEquals(vw.toFloat() / spreadW, FitGeometry.baseScale(FitMode.FIT_WIDTH, vw, vh, spreadW, ph), 1e-6f)
    }

    @Test fun `fit height fills the height even when the page then overflows sideways`() {
        val s = FitGeometry.baseScale(FitMode.FIT_HEIGHT, vw, vh, pw, ph)
        assertEquals(vh.toFloat() / ph, s, 1e-6f)
        assertEquals((pw * s - vw) / 2f, FitGeometry.maxOffsetX(vw, pw, s), 0.5f)
    }

    @Test fun `full size is one page pixel per screen pixel`() {
        assertEquals(1f, FitGeometry.baseScale(FitMode.FULL_SIZE, vw, vh, pw, ph), 0f)
    }

    @Test fun `a right-to-left reader starts at the right edge, everyone else at the left`() {
        val s = 1f
        val max = FitGeometry.maxOffsetX(vw, pw, s)
        // originX = (vw - drawW) / 2 + offset: +max aligns the left edge, -max the right edge.
        assertEquals(0f, (vw - pw * s) / 2f + FitGeometry.startOffsetX(vw, pw, s, rightToLeft = false), 0.5f)
        val rightEdge = (vw - pw * s) / 2f + FitGeometry.startOffsetX(vw, pw, s, rightToLeft = true) + pw * s
        assertEquals(vw.toFloat(), rightEdge, 0.5f)
        assertEquals(max, -FitGeometry.startOffsetX(vw, pw, s, rightToLeft = true), 0f)
    }

    @Test fun `a page that fits never offsets, whatever the flow`() {
        val s = FitGeometry.baseScale(FitMode.FIT_SCREEN, vw, vh, pw, ph)
        assertEquals(0f, FitGeometry.startOffsetX(vw, pw, s, rightToLeft = true), 0.5f)
        assertEquals(0f, FitGeometry.startOffsetY(vh, ph, s), 0.5f)
    }

    @Test fun `unknown sizes fall back to unit scale instead of dividing by zero`() {
        FitMode.entries.forEach { mode ->
            assertEquals(1f, FitGeometry.baseScale(mode, 0, vh, pw, ph), 0f)
            assertEquals(1f, FitGeometry.baseScale(mode, vw, vh, pw, 0), 0f)
        }
    }
}
