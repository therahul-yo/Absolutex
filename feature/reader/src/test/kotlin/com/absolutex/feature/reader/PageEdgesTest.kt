package com.absolutex.feature.reader

import com.absolutex.core.gpu.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trim margins and Match background are independent: turning the crop off must not turn the page
 * colour off. Pure JVM; the device check is that the letterbox tints with only Match background on.
 */
class PageEdgesTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val cream = argb(240, 230, 200)

    /** A page with a [margin]-pixel cream border around striped content that never reads as margin. */
    private fun page(w: Int, h: Int, margin: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val inside = x in margin until w - margin && y in margin until h - margin
        when {
            !inside -> cream
            x % 2 == 0 -> argb(40, 40, 40)
            else -> argb(200, 200, 200)
        }
    }

    @Test
    fun `a thumbnail is needed when crop or automatic background is on, and only then`() {
        assertTrue(needsPageThumbnail(cropActive = true, autoBackground = true))
        assertTrue(needsPageThumbnail(cropActive = true, autoBackground = false))
        assertTrue("crop off must not switch the background off", needsPageThumbnail(false, true))
        assertFalse("neither feature on decodes nothing", needsPageThumbnail(false, false))
    }

    @Test
    fun `with crop off the page edge colour is still sampled and nothing is cropped`() {
        val edges = readPageEdges(page(100, 140, margin = 10), 100, 140, detectCrop = false)
        assertNull(edges.crop)
        assertEquals(cream, edges.background)
    }

    @Test
    fun `with crop on the margin is detected and the same colour is sampled`() {
        val edges = readPageEdges(page(100, 140, margin = 10), 100, 140, detectCrop = true)
        assertEquals(CropRect(10, 10, 90, 130), edges.crop)
        assertEquals(cream, edges.background)
    }

    @Test
    fun `a full-bleed page without crop samples the whole page edge`() {
        val w = 40
        val h = 60
        val solid = argb(10, 20, 30)
        val edges = readPageEdges(IntArray(w * h) { solid }, w, h, detectCrop = false)
        assertNull(edges.crop)
        assertEquals(solid, edges.background)
    }

    @Test
    fun `corrupt pixels fall back to black rather than throwing`() {
        val edges = readPageEdges(IntArray(3), 10, 10, detectCrop = false)
        assertNull(edges.crop)
        assertEquals(0xFF000000.toInt(), edges.background)
    }
}
