package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Border-crop detection on synthetic pages (§4, milestone 4). Striped content fails coverage
 * on purpose (50% < 92%); uniform strips pass it. Every image is built pixel by pixel so the
 * expectations are exact thumb coordinates, not vibes.
 */
class CropMathTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255)
    private val black = argb(0, 0, 0)

    /** Content that never passes coverage: alternating dark/light columns. */
    private fun striped(w: Int, h: Int, dark: Int = argb(40, 40, 40), light: Int = argb(200, 200, 200)): IntArray =
        IntArray(w * h) { i -> if ((i % w) % 2 == 0) dark else light }

    private fun withMargin(content: IntArray, w: Int, h: Int, margin: Int, colour: Int): IntArray {
        val out = IntArray(w * h) { colour }
        val innerW = w - 2 * margin
        for (y in 0 until h - 2 * margin) {
            content.copyInto(out, (y + margin) * w + margin, y * innerW, (y + 1) * innerW)
        }
        return out
    }

    @Test
    fun `white margins are cropped`() {
        val w = 100
        val h = 140
        val margin = 10
        val pixels = withMargin(striped(w - 2 * margin, h - 2 * margin), w, h, margin, white)
        assertEquals(CropRect(margin, margin, w - margin, h - margin), CropMath.detect(pixels, w, h))
    }

    @Test
    fun `black margins are cropped`() {
        // Edge colour comes from the corners, never an assumption: black works like white.
        val w = 120
        val h = 100
        val margin = 12
        val bright = striped(w - 2 * margin, h - 2 * margin, argb(220, 220, 220), argb(255, 255, 240))
        val pixels = withMargin(bright, w, h, margin, black)
        assertEquals(CropRect(margin, margin, w - margin, h - margin), CropMath.detect(pixels, w, h))
    }

    @Test
    fun `noisy margins still crop`() {
        // JPEG-grade noise on the margin (±10 on every channel): tolerance 16 absorbs it while
        // striped content still fails coverage.
        val w = 100
        val h = 100
        val margin = 8
        val random = Random(42)
        val noisyWhite = IntArray(w * h) {
            val v = (255 + random.nextInt(21) - 10).coerceIn(0, 255)
            argb(v, v, v)
        }
        val content = striped(w - 2 * margin, h - 2 * margin)
        for (y in 0 until h - 2 * margin) {
            content.copyInto(noisyWhite, (y + margin) * w + margin, y * (w - 2 * margin), (y + 1) * (w - 2 * margin))
        }
        assertEquals(CropRect(margin, margin, w - margin, h - margin), CropMath.detect(noisyWhite, w, h))
    }

    @Test
    fun `a full-bleed page is not cropped`() {
        // Content to every edge: the first strip already fails coverage on all four sides.
        val w = 100
        val h = 140
        assertNull(CropMath.detect(striped(w, h), w, h))
    }

    @Test
    fun `a uniform page is not cropped`() {
        // The fail-safe: a solid page reads as one big margin, but less than half would remain.
        assertNull(CropMath.detect(IntArray(50 * 50) { white }, 50, 50))
        assertNull(CropMath.detect(IntArray(50 * 50) { black }, 50, 50))
    }

    @Test
    fun `a spread keeps its gutter`() {
        // Two striped blocks with a dark interior gutter plus outer white margins: only the
        // edge-touching strips may go. The gutter is interior, so detection never reaches it.
        val w = 200
        val h = 100
        val margin = 10
        val gutterL = 90
        val gutterR = 110
        val pixels = IntArray(w * h) { white }
        for (y in 0 until h) {
            for (x in margin until w - margin) {
                val colour = if (x in gutterL until gutterR) {
                    argb(30, 30, 30)
                } else if (x % 2 == 0) {
                    argb(40, 40, 40)
                } else {
                    argb(200, 200, 200)
                }
                pixels[y * w + x] = colour
            }
        }
        val crop = CropMath.detect(pixels, w, h)
        assertEquals(CropRect(margin, 0, w - margin, h), crop)
        assertTrue("gutter must survive inside the crop", crop!!.left < gutterL && crop.right > gutterR)
    }

    @Test
    fun `sub-threshold strips are binding glue, not margins`() {
        // A 1 px border is below MIN_MARGIN_PX: nothing is cropped.
        val w = 60
        val h = 60
        val pixels = withMargin(striped(w - 2, h - 2), w, h, 1, white)
        assertNull(CropMath.detect(pixels, w, h))
    }

    @Test
    fun `corrupt input never crops`() {
        assertNull(CropMath.detect(IntArray(0), 0, 0))
        assertNull(CropMath.detect(IntArray(10), 4, 4))
        assertNull(CropMath.detect(IntArray(16), -4, 4))
    }

    @Test
    fun `thumb rects scale to full resolution conservatively`() {
        // Thumb 100×100 over a 2000×2000 page: 10 px margins become 200 px.
        val full = CropRect(10, 10, 90, 90).scaleFrom(100, 100, 2000, 2000)
        assertEquals(CropRect(200, 200, 1800, 1800), full)
        // Odd ratios round the crop smaller, never larger.
        val odd = CropRect(1, 1, 99, 99).scaleFrom(100, 100, 1000, 1000)
        assertTrue(odd.left >= 10 && odd.top >= 10)
        assertTrue(odd.right <= 990 && odd.bottom <= 990)
    }

    @Test
    fun `full rect covers the image`() {
        assertEquals(CropRect(0, 0, 100, 140), CropRect.full(100, 140))
    }

    /** Real bitmap fixture (Android Bitmap.createBitmap), not a synthetic IntArray. */
    @Test
    fun `real bitmap page with white margins is cropped`() {
        val w = 200
        val h = 200
        val margin = 15
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { white }
        val content = striped(w - 2 * margin, h - 2 * margin)
        for (y in 0 until h - 2 * margin) {
            content.copyInto(pixels, (y + margin) * w + margin, y * (w - 2 * margin), (y + 1) * (w - 2 * margin))
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val crop = CropMath.detect(pixels, w, h)
        assertEquals(CropRect(margin, margin, w - margin, h - margin), crop)
    }

    /** A full-bleed real bitmap: detection fails safely and the full page renders. */
    @Test
    fun `failed detection on full-bleed bitmap still renders full page`() {
        val w = 120
        val h = 120
        val fullBleed = IntArray(w * h) { i -> striped(w, h)[i] }
        val result = CropMath.detect(fullBleed, w, h)
        assertTrue("no crop for full-bleed page", result == null)
    }
}
