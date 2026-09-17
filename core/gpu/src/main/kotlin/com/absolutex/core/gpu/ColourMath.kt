package com.absolutex.core.gpu

import kotlin.math.pow

/**
 * Pure-Kotlin reference for the draw-time colour pipeline (§4).
 *
 * [adjust] is the AGSL in [ColourShader.SOURCE] transcribed op-for-op in Float arithmetic, so
 * these unit tests pin the GPU's behaviour with no device: any edit that changes one side
 * without the other fails here first.
 *
 * Identity is exact only at neutral — and neutral never runs the shader (see
 * [ColourPipeline.shouldApply]) — so the 1-ulp roundoff of `(c - 0.5) + 0.5` and `pow(c, 1)`
 * is documented, not asserted.
 */
object ColourMath {

    /** Rec.709 luma weights. Must match the LUMA constant in [ColourShader.SOURCE]. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /**
     * White-balance strength: a full temperature swing at full aggression moves the red and
     * blue gains by this fraction in opposite directions. Must match WB_STRENGTH in
     * [ColourShader.SOURCE].
     */
    const val WB_STRENGTH = 0.25f

    /**
     * Contrast pivot. A named constant because detekt's MagicNumber (rightly) refuses a bare
     * 0.5 in the grade.
     */
    const val MID_GREY = 0.5f

    /**
     * Warm-hue protection inside vibrance: a fully warm pixel keeps this fraction of the boost
     * a cool pixel gets. Must match the mix literal in [ColourShader.SOURCE].
     */
    const val WARMTH_KEEP = 0.35f

    /** Combined gamma folded into each per-channel exponent. One pow per channel, not two. */
    fun foldedGamma(params: ColourParams): FloatArray =
        floatArrayOf(params.gamma * params.gammaR, params.gamma * params.gammaG, params.gamma * params.gammaB)

    /** One encoded-space pixel through [params]; result is clamped to [0, 1] like the shader. */
    fun adjust(r: Float, g: Float, b: Float, params: ColourParams): FloatArray {
        // 1. White balance along amber-blue, scaled by aggression.
        val shift = params.temperature * params.wbAggression * WB_STRENGTH
        var rr = r * (1f + shift)
        var gg = g
        var bb = b * (1f - shift)
        // 2. Contrast about mid-grey, then additive brightness.
        rr = (rr - MID_GREY) * params.contrast + MID_GREY + params.brightness
        gg = (gg - MID_GREY) * params.contrast + MID_GREY + params.brightness
        bb = (bb - MID_GREY) * params.contrast + MID_GREY + params.brightness
        // 3. Gamma on non-negative values: brightness -1 can push a channel below zero and
        // pow of a negative is NaN on both CPU and GPU.
        val exp = foldedGamma(params)
        rr = maxOf(rr, 0f).pow(exp[0])
        gg = maxOf(gg, 0f).pow(exp[1])
        bb = maxOf(bb, 0f).pow(exp[2])
        // 4. Saturation fused with vibrance: muted pixels gain proportionally to their
        // saturation deficit, warm hues less than cool ones (selective saturation).
        val luma = LUMA_R * rr + LUMA_G * gg + LUMA_B * bb
        val deficit = 1f - (maxOf(rr, gg, bb) - minOf(rr, gg, bb)).coerceIn(0f, 1f)
        val warmth = ((1f + rr - bb) / 2f).coerceIn(0f, 1f)
        val selectivity = deficit * (1f + (WARMTH_KEEP - 1f) * warmth)
        val factor = params.saturation + params.vibrance * selectivity
        rr = luma + (rr - luma) * factor
        gg = luma + (gg - luma) * factor
        bb = luma + (bb - luma) * factor
        return floatArrayOf(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
    }

    fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b
}

/**
 * How a bitmap lands in the corrected draw.
     *
     * The shader path draws `drawRect(dst)` instead of `drawBitmap(src, dst)` — Skia replaces a
     * paint's shader with the image shader on bitmap draws, so the effect would silently never run —
     * and positions the content with a local matrix on the child BitmapShader. The local matrix maps
     * bitmap coordinates to canvas coordinates: `canvas = M · bitmap`, so sampling applies `M⁻¹`,
     * which maps canvas → bitmap as `(frag - origin) * (bitmap / dst)`.
     *
     * The matrix is `T(origin) · S(dst/bitmap)`: scale by `dst/bitmap`, then translate by the
     * destination origin. In Android's API that is `setScale` then `postTranslate`.
     *
     * Pure floats, so the mapping is unit-tested here and applied to a hoisted Matrix in
     * [ColourPipeline] with no per-frame allocation.
     */
    data class ContentMatrix(
        val scaleX: Float,
        val scaleY: Float,
        val transX: Float,
        val transY: Float,
    )

    /**
     * Matrix for a bitmap of [bitmapW]×[bitmapH] drawn in full into the integer rect
     * ([left], [top], [right], [bottom]). Degenerate rects coerce to 1 px: the draw call sites
     * already guarantee non-empty rects, and a zero divisor here would NaN the whole page.
     */
    fun contentMatrix(
        srcLeft: Int,
        srcTop: Int,
        srcRight: Int,
        srcBottom: Int,
        dstLeft: Int,
        dstTop: Int,
        dstRight: Int,
        dstBottom: Int,
    ): ContentMatrix {
        val dw = maxOf(1, dstRight - dstLeft).toFloat()
        val dh = maxOf(1, dstBottom - dstTop).toFloat()
        val sw = maxOf(1, srcRight - srcLeft).toFloat()
        val sh = maxOf(1, srcBottom - srcTop).toFloat()
        // dst = src * scale + translate → scale = dst/src, translate = dst.origin − src.origin * scale
        val sx = dw / sw
        val sy = dh / sh
        return ContentMatrix(sx, sy, dstLeft.toFloat() - srcLeft * sx, dstTop.toFloat() - srcTop * sy)
    }
