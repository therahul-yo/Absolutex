package com.absolutex.core.gpu

/**
 * Pure-Kotlin reference for the draw-time colour pipeline (§4).
 *
 * [adjust] is the AGSL in [ColourShader.SOURCE] transcribed op-for-op in Float arithmetic:
 * contrast about mid-grey, additive brightness, saturation as a luma blend, explicit clamp.
 * Because the shader is a direct transcription, these unit tests pin the GPU's behaviour with
 * no device: any edit that changes one side without the other fails here first.
 *
 * Identity is exact only at neutral — and neutral never runs the shader (see
 * [ColourPipeline.shouldApply]) — so the 1-ulp roundoff of `(c - 0.5) + 0.5` is documented,
 * not asserted.
 */
object ColourMath {

    /** Rec.709 luma weights. Must match the LUMA constant in [ColourShader.SOURCE]. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** One encoded-space pixel through [params]; result is clamped to [0, 1] like the shader. */
    fun adjust(r: Float, g: Float, b: Float, params: ColourParams): FloatArray {
        var rr = (r - 0.5f) * params.contrast + 0.5f + params.brightness
        var gg = (g - 0.5f) * params.contrast + 0.5f + params.brightness
        var bb = (b - 0.5f) * params.contrast + 0.5f + params.brightness
        val luma = LUMA_R * rr + LUMA_G * gg + LUMA_B * bb
        rr = luma + (rr - luma) * params.saturation
        gg = luma + (gg - luma) * params.saturation
        bb = luma + (bb - luma) * params.saturation
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
 * destination origin. In Android's API that is `setScale` then `preTranslate`.
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
 * Matrix mapping the source rect ([srcLeft], [srcTop], [srcRight], [srcBottom]) in bitmap
 * space to the destination rect ([dstLeft], [dstTop], [dstRight], [dstBottom]) in canvas
 * space. Degenerate rects coerce to 1 px: the draw call sites already guarantee non-empty
 * rects, and a zero divisor here would NaN the whole page.
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
    val srcW = maxOf(1, srcRight - srcLeft).toFloat()
    val srcH = maxOf(1, srcBottom - srcTop).toFloat()
    val dstW = maxOf(1, dstRight - dstLeft).toFloat()
    val dstH = maxOf(1, dstBottom - dstTop).toFloat()
    val sx = dstW / srcW
    val sy = dstH / srcH
    val tx = dstLeft - srcLeft * sx
    val ty = dstTop - srcTop * sy
    return ContentMatrix(sx, sy, tx, ty)
}
