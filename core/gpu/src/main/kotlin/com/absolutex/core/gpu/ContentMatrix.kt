package com.absolutex.core.gpu

/**
 * How a bitmap lands in the corrected draw.
 *
 * The shader path draws `drawRect(dst)` instead of `drawBitmap(src, dst)` — Skia replaces a
 * paint's shader with the image shader on bitmap draws, so the effect would silently never run —
 * and positions the content with a local matrix on the child BitmapShader. The matrix maps a
 * canvas coordinate to the bitmap pixel drawn there: `pixel = (frag - dst.origin) * bitmap / dst`.
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
    bitmapW: Int,
    bitmapH: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): ContentMatrix {
    val w = maxOf(1, right - left).toFloat()
    val h = maxOf(1, bottom - top).toFloat()
    val sx = bitmapW / w
    val sy = bitmapH / h
    return ContentMatrix(sx, sy, -left * sx, -top * sy)
}
