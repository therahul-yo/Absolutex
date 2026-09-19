package com.absolutex.core.gpu

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Which resampling filter magnifies a bitmap drawn above its base resolution (§4, milestone 3).
 *
 * PLATFORM is bilinear from the hardware sampler: free, and what gestures always use. MITCHELL
 * (Mitchell–Netravali, B = C = 1/3) and LANCZOS (Lanczos-3) run as multi-tap kernels in the
 * colour shader's sampling stage, applied only when the page is at rest — during a gesture the
 * draw falls back to PLATFORM, so the kernel's ALU never lands on a gesture frame. Lifting the
 * fingers refines the page with one repaint.
 *
 * Codes are frozen (they cross into AGSL as an int uniform): never reorder, append only.
 */
enum class Upscaler(val code: Int) {
    PLATFORM(0),
    MITCHELL(1),
    LANCZOS(2);

    companion object {
        /**
         * Second benchmark intent extra ([EXTRA_UPSCALER]), kept separate from the colour extra
         * so each codec stays total on its own: unknown or absent is PLATFORM, never a crash.
         */
        const val EXTRA_UPSCALER = "com.absolutex.gpu.UPSCALER"

        fun decodeExtra(raw: String?): Upscaler =
            entries.firstOrNull { it.name.lowercase() == raw } ?: PLATFORM
    }
}

/**
 * Pure-Kotlin reference for the upscaling kernels. [UpscaleMath.mitchell] and
 * [UpscaleMath.lanczos3] are the AGSL weight functions transcribed; [UpscaleMath.resampleChannel]
 * is the whole 2D separable sample, used to prove the algorithm (partition of unity, edge
 * renormalisation) with no device.
 *
 * Convention, shared with the shader: source texel (i, j) spans [i, i+1] × [j, j+1], so integer
 * coordinates are texel corners. The 2r taps nearest p start at floor(p) − r + 1 — sampling
 * texel centres at base + k + 0.5 — which keeps the window centred on p instead of sliding a
 * whole texel past it (the impulse test pins this: a half-texel error reads 0.89, not 0.79).
 */
object UpscaleMath {

    /** Lanczos window radius in texels: 3 × 3 support, 6 × 6 taps. */
    const val LANCZOS_RADIUS = 3

    /** Mitchell support radius in texels: 2 × 2 support, 4 × 4 taps. */
    const val MITCHELL_RADIUS = 2

    fun supportRadius(upscaler: Upscaler): Int = when (upscaler) {
        Upscaler.MITCHELL -> MITCHELL_RADIUS
        Upscaler.LANCZOS -> LANCZOS_RADIUS
        Upscaler.PLATFORM -> 0
    }

    fun mitchell(x: Float): Float {
        val ax = abs(x)
        return if (ax < 1f) {
            (M1_C3 * ax * ax * ax + M1_C2 * ax * ax + M1_C0) / M_DIV
        } else if (ax < 2f) {
            (M2_C3 * ax * ax * ax + M2_C2 * ax * ax + M2_C1 * ax + M2_C0) / M_DIV
        } else {
            0f
        }
    }

    fun lanczos3(x: Float): Float {
        val ax = abs(x)
        if (ax >= LANCZOS_RADIUS) return 0f
        if (ax == 0f) return 1f
        return (sinc(ax) * sinc(ax / LANCZOS_RADIUS)).toFloat()
    }

    private fun sinc(x: Float): Double {
        val pix = Math.PI * x
        return sin(pix) / pix
    }

    fun kernelWeight(upscaler: Upscaler, distance: Float): Float = when (upscaler) {
        Upscaler.MITCHELL -> mitchell(distance)
        Upscaler.LANCZOS -> lanczos3(distance)
        Upscaler.PLATFORM -> throw IllegalArgumentException("platform sampling is the hardware sampler, not a kernel")
    }

    /**
     * One channel resampled at source-pixel ([x], [y]) through [upscaler]'s kernel, clamp-to-edge
     * with renormalisation: border taps reuse the edge texel, and the accumulator divides by the
     * weights actually used, so a constant image stays constant right up to the edge.
     */
    fun resampleChannel(src: FloatArray, width: Int, height: Int, x: Float, y: Float, upscaler: Upscaler): Float {
        require(upscaler != Upscaler.PLATFORM) { "platform sampling is the hardware sampler" }
        require(src.size == width * height) { "pixels do not match dimensions" }
        val radius = supportRadius(upscaler)
        val baseX = floor(x).toInt() - radius + 1
        val baseY = floor(y).toInt() - radius + 1
        var acc = 0f
        var weightSum = 0f
        for (j in 0 until radius * 2) {
            val sy = (baseY + j).coerceIn(0, height - 1)
            val wy = kernelWeight(upscaler, baseY + j + HALF_TEXEL - y)
            for (i in 0 until radius * 2) {
                val sx = (baseX + i).coerceIn(0, width - 1)
                val wx = kernelWeight(upscaler, baseX + i + HALF_TEXEL - x)
                acc += src[sy * width + sx] * wx * wy
                weightSum += wx * wy
            }
        }
        return acc / weightSum
    }
}

// Mitchell–Netravali with B = C = 1/3, expanded: |x| < 1 is (7x^3 − 12x^2 + 16/3) / 6,
// 1 ≤ |x| < 2 is ((−7/3)x^3 + 12x^2 − 20x + 32/3) / 6. The balanced pair: mild sharpening,
// bounded ringing. mitchell(0) = 16/18 pins the choice in UpscaleMathTest.
private const val M1_C3 = 7f
private const val M1_C2 = -12f
private const val M1_C0 = 16f / 3f
private const val M2_C3 = -7f / 3f
private const val M2_C2 = 12f
private const val M2_C1 = -20f
private const val M2_C0 = 32f / 3f
private const val M_DIV = 6f

/** Texel-centre offset: the tap at (base + k) samples (base + k + 0.5). */
private const val HALF_TEXEL = 0.5f

/**
 * True when the draw rect is larger than the bitmap in either axis: the draw magnifies, and a
 * kernel upscaler has work the platform sampler would blur. Strictly above base resolution —
 * a 1:1 draw stays on the hardware path whatever is selected.
 */
fun isMagnifying(srcW: Int, srcH: Int, dstLeft: Int, dstTop: Int, dstRight: Int, dstBottom: Int): Boolean =
    dstRight - dstLeft > srcW || dstBottom - dstTop > srcH
