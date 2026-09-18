package com.absolutex.core.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
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
    fun `white balance warms a known blue cast`() {
        // Full warm shift at full aggression: red ×1.25, green untouched, blue ×0.75.
        check(
            0.5f, 0.55f, 0.75f,
            ColourParams(temperature = 1f, wbAggression = 1f),
            floatArrayOf(0.625f, 0.55f, 0.5625f),
        )
    }

    @Test
    fun `white balance cools symmetrically`() {
        // Full cool shift: red ×0.75, blue ×1.25.
        check(
            0.5f, 0.55f, 0.75f,
            ColourParams(temperature = -1f, wbAggression = 1f),
            floatArrayOf(0.375f, 0.55f, 0.9375f),
        )
    }

    @Test
    fun `zero aggression disables white balance`() {
        val cast = floatArrayOf(0.5f, 0.55f, 0.75f)
        check(cast[0], cast[1], cast[2], ColourParams(temperature = 1f, wbAggression = 0f), cast)
    }

    @Test
    fun `gamma darkens mid-grey by the power law`() {
        // 0.25 ^ 2 = 0.0625 on every channel; luma of grey is itself, so saturation is a no-op.
        check(0.25f, 0.25f, 0.25f, ColourParams(gamma = 2f), floatArrayOf(0.0625f, 0.0625f, 0.0625f))
    }

    @Test
    fun `gamma round-trips`() {
        // 2.2 then its inverse must restore the pixel: the stage is invertible, not lossy.
        val pixel = floatArrayOf(0.2f, 0.5f, 0.8f)
        val graded = ColourMath.adjust(pixel[0], pixel[1], pixel[2], ColourParams(gamma = 2.2f))
        val back = ColourMath.adjust(graded[0], graded[1], graded[2], ColourParams(gamma = 1f / 2.2f))
        assertArrayEquals(pixel, back, 1e-5f)
    }

    @Test
    fun `per-channel gamma corrects a cast without touching other channels`() {
        // Red 0.5^2 = 0.25; green and blue ride the identity exponent and survive, since the
        // saturation stage blends toward luma with factor 1 (vibrance off) — a no-op.
        check(0.5f, 0.5f, 0.5f, ColourParams(gammaR = 2f), floatArrayOf(0.25f, 0.5f, 0.5f))
    }

    @Test
    fun `negative channels floor before gamma instead of NaN`() {
        // Brightness -1 drives every channel below zero; max(c, 0) must catch it before pow.
        val out = ColourMath.adjust(0.2f, 0.4f, 0.6f, ColourParams(brightness = -1f, gamma = 2f))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 0f)
        for (channel in out) assertTrue(channel.isFinite())
    }

    @Test
    fun `vibrance boosts a muted pixel by the selective factor`() {
        // Hand-computed: luma 0.48596, deficit 0.8, warmth 0.4, selectivity 0.592, factor 1.592.
        val out = ColourMath.adjust(0.4f, 0.5f, 0.6f, ColourParams(vibrance = 1f))
        assertArrayEquals(floatArrayOf(0.3491f, 0.5083f, 0.6675f), out, 1e-4f)
    }

    @Test
    fun `vibrance leaves a fully saturated pixel alone`() {
        // Deficit zero kills the boost whatever the warmth: pure red is unchanged.
        check(1f, 0f, 0f, ColourParams(vibrance = 1f), floatArrayOf(1f, 0f, 0f))
    }

    @Test
    fun `vibrance favours cool hues over warm ones at the same deficit`() {
        // Same saturation deficit (0.15), mirrored warmth: the cool pixel must spread further.
        val cool = ColourMath.adjust(0.4f, 0.45f, 0.55f, ColourParams(vibrance = 1f))
        val warm = ColourMath.adjust(0.55f, 0.45f, 0.4f, ColourParams(vibrance = 1f))
        val spreadCool = cool.max() - cool.min()
        val spreadWarm = warm.max() - warm.min()
        assertTrue("cool $spreadCool should exceed warm $spreadWarm", spreadCool > spreadWarm)
    }

    @Test
    fun `folded gamma multiplies combined with per-channel`() {
        val folded = ColourMath.foldedGamma(
            ColourParams(gamma = 2f, gammaR = 0.5f, gammaG = 1f, gammaB = 0.25f),
        )
        assertArrayEquals(floatArrayOf(1f, 2f, 0.5f), folded, 0f)
    }

    @Test
    fun `benchmark values stay in gamut on mid-tones`() {
        // The GpuBenchmark non-neutral set must grade, not clip, a typical page: no channel
        // of mid-grey may pin to an endpoint.
        val out = ColourMath.adjust(0.5f, 0.5f, 0.5f, ColourParams(0.15f, 1.1f, 1.25f))
        for (channel in out) {
            val inside = channel > 0f && channel < 1f
            assertTrue("channel pinned at $channel", inside)
        }
    }
}
