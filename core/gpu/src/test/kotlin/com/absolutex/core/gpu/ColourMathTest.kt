package com.absolutex.core.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins the JVM reference the AGSL transcribes. Hand-computed spot checks, not just
 * self-consistency: if the shader drifts from this file, these fail first.
 */
class ColourMathTest {

    private fun check(r: Float, g: Float, b: Float, params: ColourParams, expected: FloatArray) {
        assertArrayEquals(expected, ColourMath.adjust(r, g, b, params), 1e-6f)
    }

    @Test
    fun `neutral is the identity`() {
        val samples = arrayOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.2f, 0.5f, 0.8f),
            floatArrayOf(1f, 0f, 0f),
        )
        for (s in samples) {
            check(s[0], s[1], s[2], ColourParams.NEUTRAL, s)
        }
    }

    @Test
    fun `full brightness clips to white`() {
        check(0.2f, 0.4f, 0.6f, ColourParams(brightness = 1f), floatArrayOf(1f, 1f, 1f))
    }

    @Test
    fun `full negative brightness clips to black`() {
        check(0.2f, 0.4f, 0.6f, ColourParams(brightness = -1f), floatArrayOf(0f, 0f, 0f))
    }

    @Test
    fun `zero contrast is flat mid-grey`() {
        // (c - 0.5) * 0 + 0.5 + 0 = 0.5 on every channel; saturation has nothing to blend.
        check(0.2f, 0.4f, 0.9f, ColourParams(contrast = 0f), floatArrayOf(0.5f, 0.5f, 0.5f))
    }

    @Test
    fun `zero saturation is rec709 luma`() {
        // Pure red: luma = 0.2126, and the saturation blend collapses every channel onto it.
        val luma = ColourMath.luma(1f, 0f, 0f)
        check(1f, 0f, 0f, ColourParams(saturation = 0f), floatArrayOf(luma, luma, luma))
    }

    @Test
    fun `contrast pivots about mid-grey`() {
        // 0.75 is 0.25 above mid; doubled contrast puts it 0.5 above: exactly white.
        check(0.75f, 0.75f, 0.75f, ColourParams(contrast = 2f), floatArrayOf(1f, 1f, 1f))
        // Symmetry below mid: 0.25 lands exactly on black.
        check(0.25f, 0.25f, 0.25f, ColourParams(contrast = 2f), floatArrayOf(0f, 0f, 0f))
    }

    @Test
    fun `benchmark values stay in gamut on mid-tones`() {
        // The GpuBenchmark non-neutral set must grade, not clip, a typical page: no channel
        // of mid-grey may pin to an endpoint.
        val out = ColourMath.adjust(0.5f, 0.5f, 0.5f, ColourParams(0.15f, 1.1f, 1.25f))
        for (channel in out) {
            val inside = channel > 0f && channel < 1f
            org.junit.Assert.assertTrue("channel pinned at $channel", inside)
        }
    }
}
