package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the upscaling kernels (§4, milestone 3): weights sum to 1 (brightness preservation),
 * are symmetric, and have the documented support. Hand-computed spot checks plus the
 * partition-of-unity property the shader relies on.
 */
class UpscaleMathTest {

    private val fractions = floatArrayOf(0f, 0.13f, 0.37f, 0.5f, 0.71f, 0.93f)

    private fun weightSum(upscaler: Upscaler, x: Float): Float {
        val radius = UpscaleMath.supportRadius(upscaler)
        var sum = 0f
        for (i in -radius..radius) {
            sum += UpscaleMath.kernelWeight(upscaler, x + i)
        }
        return sum
    }

    @Test
    fun `mitchell centre weight pins the B equals C equals one-third choice`() {
        // (16/3) / 6 = 16/18: any other B/C pair lands elsewhere.
        assertEquals(16f / 18f, UpscaleMath.mitchell(0f), 1e-6f)
    }

    @Test
    fun `mitchell weights are symmetric`() {
        for (x in floatArrayOf(0f, 0.25f, 0.7f, 1f, 1.5f, 1.99f, 2.5f)) {
            assertEquals("at $x", UpscaleMath.mitchell(x), UpscaleMath.mitchell(-x), 0f)
        }
    }

    @Test
    fun `mitchell has radius-two support`() {
        assertEquals(0f, UpscaleMath.mitchell(2f), 0f)
        assertEquals(0f, UpscaleMath.mitchell(-2.7f), 0f)
        assertEquals(2, UpscaleMath.supportRadius(Upscaler.MITCHELL))
    }

    @Test
    fun `mitchell weights sum to one at any phase`() {
        // Partition of unity: a constant image resamples to itself, no brightness shift.
        for (frac in fractions) {
            assertEquals("at $frac", 1f, weightSum(Upscaler.MITCHELL, frac), 1e-5f)
        }
    }

    @Test
    fun `lanczos weights are symmetric`() {
        for (x in floatArrayOf(0f, 0.3f, 1f, 1.8f, 2.5f, 2.99f, 4f)) {
            assertEquals("at $x", UpscaleMath.lanczos3(x), UpscaleMath.lanczos3(-x), 0f)
        }
    }

    @Test
    fun `lanczos has radius-three support`() {
        assertEquals(0f, UpscaleMath.lanczos3(3f), 0f)
        assertEquals(0f, UpscaleMath.lanczos3(-3.4f), 0f)
        assertEquals(3, UpscaleMath.supportRadius(Upscaler.LANCZOS))
    }

    @Test
    fun `lanczos interpolates, one at zero and zero at the other integers`() {
        assertEquals(1f, UpscaleMath.lanczos3(0f), 0f)
        for (n in intArrayOf(1, -1, 2, -2)) {
            assertEquals("at $n", 0f, UpscaleMath.lanczos3(n.toFloat()), 1e-6f)
        }
    }

    @Test
    fun `lanczos weights sum to almost one at any phase`() {
        // Truncated sinc does not preserve DC exactly (worst ~5e-3 off-phase): the resampler
        // renormalises, so constant images still survive exactly — see below. This pins the
        // deviation where the literature puts it, not at zero.
        for (frac in fractions) {
            assertEquals("at $frac", 1f, weightSum(Upscaler.LANCZOS, frac), 6e-3f)
        }
    }

    @Test
    fun `a constant image resamples to itself, edges included`() {
        // Edges exercise the clamp-plus-renormalise path: without it the border would dim.
        for (upscaler in listOf(Upscaler.MITCHELL, Upscaler.LANCZOS)) {
            val grey = FloatArray(8 * 8) { 0.6f }
            for (y in floatArrayOf(0.1f, 0.5f, 3.7f, 7.4f, 7.9f)) {
                for (x in floatArrayOf(0.1f, 0.5f, 2.3f, 7.4f, 7.9f)) {
                    val out = UpscaleMath.resampleChannel(grey, 8, 8, x, y, upscaler)
                    assertEquals("$upscaler at ($x, $y)", 0.6f, out, 1e-5f)
                }
            }
        }
    }

    @Test
    fun `an impulse resamples to the kernel footprint`() {
        // Single hot texel in a black field, sampled at its centre: only its own taps fire,
        // so the result is the centre weight squared — the plumbing check, not the kernel.
        val field = FloatArray(9 * 9)
        field[4 * 9 + 4] = 1f
        val mitchellOut = UpscaleMath.resampleChannel(field, 9, 9, 4.5f, 4.5f, Upscaler.MITCHELL)
        val centre = UpscaleMath.mitchell(0f)
        assertEquals(centre * centre, mitchellOut, 1e-6f)
    }

    @Test
    fun `platform has no kernel`() {
        assertEquals(0, UpscaleMath.supportRadius(Upscaler.PLATFORM))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `platform rejects kernel sampling`() {
        UpscaleMath.resampleChannel(FloatArray(4), 2, 2, 0.5f, 0.5f, Upscaler.PLATFORM)
    }

    @Test
    fun `upscaler extra round-trips and tolerates absence`() {
        assertEquals(Upscaler.LANCZOS, Upscaler.decodeExtra("lanczos"))
        assertEquals(Upscaler.MITCHELL, Upscaler.decodeExtra("mitchell"))
        assertEquals(Upscaler.PLATFORM, Upscaler.decodeExtra(null))
        assertEquals(Upscaler.PLATFORM, Upscaler.decodeExtra("bilinear"))
        assertEquals(Upscaler.PLATFORM, Upscaler.decodeExtra("LANCZOS"))
    }

    @Test
    fun `magnification is strictly above base resolution`() {
        assertTrue(isMagnifying(100, 100, 0, 0, 200, 200))
        assertTrue(isMagnifying(100, 100, 0, 0, 100, 200))
        assertTrue(isMagnifying(100, 100, 0, 0, 200, 100))
        assertTrue(!isMagnifying(100, 100, 0, 0, 100, 100))
        assertTrue(!isMagnifying(200, 200, 0, 0, 100, 100))
    }

    @Test
    fun `kernel weights stay bounded`() {
        // A sanity bound on ringing: no tap may exceed the centre weight by far.
        for (upscaler in listOf(Upscaler.MITCHELL, Upscaler.LANCZOS)) {
            val centre = abs(UpscaleMath.kernelWeight(upscaler, 0f))
            var tenth = 0f
            while (tenth <= UpscaleMath.supportRadius(upscaler)) {
                assertTrue(
                    "$upscaler at $tenth",
                    abs(UpscaleMath.kernelWeight(upscaler, tenth)) <= centre * 1.5f,
                )
                tenth += 0.1f
            }
        }
    }
}
