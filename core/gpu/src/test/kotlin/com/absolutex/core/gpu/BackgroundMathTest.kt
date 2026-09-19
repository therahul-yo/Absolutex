package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Auto background sampling (§4, §5.2, milestone 5): the border mean, deterministic and total.
 */
class BackgroundMathTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a solid page samples to itself`() {
        val white = IntArray(10 * 10) { argb(255, 255, 255) }
        assertEquals(argb(255, 255, 255), BackgroundMath.sampleEdge(white, 10, 10, null))
        val black = IntArray(8 * 12) { argb(0, 0, 0) }
        assertEquals(argb(0, 0, 0), BackgroundMath.sampleEdge(black, 8, 12, null))
    }

    @Test
    fun `the border wins over the centre`() {
        // White frame, black middle: only the outer two rows/columns count.
        val w = 10
        val h = 10
        val pixels = IntArray(w * h) { argb(255, 255, 255) }
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                pixels[y * w + x] = argb(0, 0, 0)
            }
        }
        assertEquals(argb(255, 255, 255), BackgroundMath.sampleEdge(pixels, w, h, null))
    }

    @Test
    fun `a crop samples its own border`() {
        // Striped full image, crop on the dark column pair: the crop border is dark throughout.
        val w = 10
        val h = 10
        val pixels = IntArray(w * h) { i -> if ((i % w) % 2 == 0) argb(20, 20, 20) else argb(230, 230, 230) }
        val out = BackgroundMath.sampleEdge(pixels, w, h, CropRect(0, 0, 2, h))
        // Columns 0–1: col 0 dark, col 1 light, averaged over the border strips.
        val mean = (20 + 230) / 2
        assertEquals(argb(mean, mean, mean), out)
    }

    @Test
    fun `sampling is deterministic`() {
        // Same pixels, same colour, every run: no randomness, no timing, no device state.
        val pixels = IntArray(12 * 12) { i -> argb(i % 256, (i * 2) % 256, (i * 3) % 256) }
        val first = BackgroundMath.sampleEdge(pixels, 12, 12, CropRect(1, 1, 11, 11))
        val second = BackgroundMath.sampleEdge(pixels, 12, 12, CropRect(1, 1, 11, 11))
        assertEquals(first, second)
    }

    @Test
    fun `corrupt input falls back to opaque black, never throws`() {
        val pixels = IntArray(4 * 4) { argb(200, 200, 200) }
        assertEquals(BackgroundMath.FALLBACK, BackgroundMath.sampleEdge(IntArray(0), 0, 0, null))
        assertEquals(BackgroundMath.FALLBACK, BackgroundMath.sampleEdge(IntArray(10), 4, 4, null))
        assertEquals(BackgroundMath.FALLBACK, BackgroundMath.sampleEdge(pixels, 4, 4, CropRect(2, 2, 2, 2)))
    }

    @Test
    fun `a wild crop clamps to the image instead of throwing`() {
        val pixels = IntArray(4 * 4) { argb(200, 200, 200) }
        assertEquals(
            BackgroundMath.sampleEdge(pixels, 4, 4, null),
            BackgroundMath.sampleEdge(pixels, 4, 4, CropRect(-9, -9, 99, 99)),
        )
    }

    @Test
    fun `a one-pixel image samples itself`() {
        val red = argb(200, 30, 30)
        assertEquals(red, BackgroundMath.sampleEdge(intArrayOf(red), 1, 1, null))
    }
}
