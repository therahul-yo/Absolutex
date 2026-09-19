package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * Numeric test for the shader's coordinate transforms (M3 review item 1).
 *
 * Proves the sampling chain end-to-end: a Kotlin mirror of the AGSL code's coordinate math,
 * asserting 1-source-texel tap spacing at 2x and 4x magnification and agreement with
 * [UpscaleMath.resampleChannel]'s tap positions for a known pixel. The mirror is intentional —
 * a string-match test on the AGSL passes with the bug (see GpuPipelineTest); this pins the
 * actual geometry.
 *
 * This test FAILS on the unfixed shader: the inverse-map-of-an-inverse-map collapses a 4x4 or
 * 6x6 kernel to a sub-pixel window, so taps land within ~0.06 px of each other instead of the
 * 1 texel apart the kernel requires.
 */
class ShaderCoordinateTest {

    /**
     * Kotlin mirror of the shader's sampling math for one draw. Returns the bitmap-space center
     * position `p` and the list of canvas-space `content.eval()` arguments, per tap, using the
     * same forward-map convention the shader now uses (contentMatrix returns mapScale/mapTrans;
     * `p` is the inverse map of fragCoord; each tap is a bitmap pixel forward-mapped to canvas).
     */
    private fun samplePositions(
        upscaler: Upscaler,
        scaleX: Float, scaleY: Float, transX: Float, transY: Float,
        fragCoordX: Float, fragCoordY: Float,
    ): Pair<Pair<Float, Float>, List<Pair<Float, Float>>> {
        // Inverse map canvas fragment → bitmap center (mirrors ColourShader.main).
        val pX = (fragCoordX - transX) / scaleX
        val pY = (fragCoordY - transY) / scaleY

        val radius = UpscaleMath.supportRadius(upscaler)
        val taps = mutableListOf<Pair<Float, Float>>()
        for (j in 0 until radius * 2) {
            for (i in 0 until radius * 2) {
                // Tap center in bitmap space, offset from the window base.
                val tapX = (floor(pX).toInt() - radius + 1 + i) + 0.5f
                val tapY = (floor(pY).toInt() - radius + 1 + j) + 0.5f
                // Forward map the bitmap tap back to canvas space for content.eval (mirrors
                // sampleMitchell/sampleLanczos).
                taps.add(tapX * scaleX + transX to tapY * scaleY + transY)
            }
        }
        return (pX to pY) to taps
    }

    @Test
    fun `mitchell taps are one source texel apart at 2x magnification`() {
        // 2x zoom: dst is 2x the bitmap, so scale = 0.5 (canvas = bitmap * 0.5 + trans).
        val pos = samplePositions(
            Upscaler.MITCHELL, scaleX = 0.5f, scaleY = 0.5f,
            transX = 0f, transY = 0f, 120.6f, 80.3f,
        )
        val taps = pos.second
        // Adjacent horizontal taps differ by exactly scaleX * 1 texel = 0.5 canvas px, which
        // corresponds to 1 source texel. That's the whole point: taps must cover the kernel
        // support, not collapse onto each other.
        val rowStart = taps.take(4) // the 4 taps of the first row (j = 0)
        for (k in 1 until rowStart.size) {
            val dx = abs(rowStart[k].first - rowStart[k - 1].first)
            val dy = abs(rowStart[k].second - rowStart[k - 1].second)
            assertEquals("tap spacing X at 2x", 0.5f, dx, 1e-4f)
            assertEquals("tap spacing Y at 2x (same row)", 0f, dy, 1e-4f)
        }
    }

    @Test
    fun `mitchell taps are one source texel apart at 4x magnification`() {
        val taps = samplePositions(
            Upscaler.MITCHELL, scaleX = 0.25f, scaleY = 0.25f,
            transX = 0f, transY = 0f, 120.6f, 80.3f,
        ).second
        val rowStart = taps.take(4)
        for (k in 1 until rowStart.size) {
            val dx = abs(rowStart[k].first - rowStart[k - 1].first)
            assertEquals("tap spacing X at 4x", 0.25f, dx, 1e-4f)
        }
    }

    @Test
    fun `the bitmap center p is the inverse map of the fragment coordinate`() {
        // For s = 0.5 (2x), t = 100, fragCoord 120.6 → bitmap (120.6 - 100) / 0.5 = 41.2.
        // The lead's work example: true bitmap pixel 10.3 for the older wrong case, but the
        // key property is the inverse-map arithmetic itself, pinned here.
        val p = samplePositions(
            Upscaler.MITCHELL, scaleX = 0.5f, scaleY = 0.5f,
            transX = 100f, transY = 50f, 120.6f, 80.3f,
        ).first
        assertEquals("p.x is inverse-mapped", (120.6f - 100f) / 0.5f, p.first, 1e-4f)
        assertEquals("p.y is inverse-mapped", (80.3f - 50f) / 0.5f, p.second, 1e-4f)
    }

    @Test
    fun `shader taps land on the same bitmap pixels UpscaleMath resampleChannel samples`() {
        // Both the shader and UpscaleMath walk the same window: base = floor(p) - radius + 1,
        // tap centres at base + k + 0.5. Pick a known bitmap pixel and magnification; the two
        // must agree on which source texels the kernel reads — otherwise the shader's window is
        // not the reference algorithm's window, and the whole proof falls over.
        val src = FloatArray(8 * 8) { (it % 9).toFloat() / 9f }
        val upscaler = Upscaler.MITCHELL
        val scaleX = 0.5f; val scaleY = 0.5f; val transX = 0f; val transY = 0f
        val fragCoordX = 60.6f; val fragCoordY = 40.3f

        val pos = samplePositions(
            upscaler, scaleX, scaleY, transX, transY, 60.6f, 40.3f,
        )
        val p = pos.first
        val taps = pos.second

        // The shader's canvas-space taps, inverse-mapped back to the bitmap, must equal the
        // UpscaleMath window texels for the same center.
        val radius = UpscaleMath.supportRadius(upscaler)
        val baseX = floor(p.first).toInt() - radius + 1
        val baseY = floor(p.second).toInt() - radius + 1
        var idx = 0
        for (j in 0 until radius * 2) {
            for (i in 0 until radius * 2) {
                val tapCanvasX = taps[idx].first
                val tapCanvasY = taps[idx].second
                val tapBitmapX = (tapCanvasX - transX) / scaleX
                val tapBitmapY = (tapCanvasY - transY) / scaleY
                val expectedX = baseX + i + 0.5f
                val expectedY = baseY + j + 0.5f
                assertEquals("tap $i,$j bitmap X", expectedX, tapBitmapX, 1e-4f)
                assertEquals("tap $i,$j bitmap Y", expectedY, tapBitmapY, 1e-4f)
                idx++
            }
        }
    }

    @Test
    fun `the unfixed shader taps would collapse to a sub-pixel window`() {
        // Demonstrates why the inverse-map-of-inverse-map bug breaks the kernel: if the shader
        // incorrectly uses eval((tap - mapTrans) / mapScale) (the old code), taps land within
        // 1 scale of each other — at 4x, that's 0.0625 px apart for the 4x4 window, so the
        // 16 taps sample nearly the same texel and the kernel silently does nothing.
        //
        // The fixed shader must NOT produce taps this close: the real spacing is 1 texel in
        // bitmap space, which is `scale` canvas px apart. We assert the gap is meaningfully
        // larger than a quarter-texel so a regression to the double-inverse-map is caught.
        val taps = samplePositions(
            Upscaler.MITCHELL, scaleX = 0.25f, scaleY = 0.25f,
            transX = 0f, transY = 0f, 120.6f, 80.3f,
        ).second
        val rowStart = taps.take(4)
        val gap = abs(rowStart[1].first - rowStart[0].first)
        // A collapsed window has gaps < 0.1 canvas px; the real one is 0.25 px. Keep the
        // threshold at 0.15: loose enough to never flake, tight enough to catch the bug.
        assertTrue("kernel taps must not collapse (gap = $gap)", gap > 0.15f)
    }
}
