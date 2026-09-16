package com.absolutex.core.gpu

import kotlin.math.min

/**
 * Smart border-crop detection (§4, milestone 4): pure Kotlin over an IntArray of ARGB pixels.
 *
 * Only edge-touching uniform strips are ever cropped — an interior gutter never touches an edge,
 * so spreads survive by construction — and a fail-safe rejects any crop that would eat half the
 * page (a flat dark full-bleed reads as one big margin otherwise). The caller (PageCanvas) runs
 * this on a ~96 px thumbnail off the main thread before the first paint, then scales the rect
 * to full resolution; the thumbnail error is ±1 thumb pixel either way.
 *
 * Why a thumbnail and not the GPU: fragment shaders have no write-back channel (no SSBO or
 * compute in AGSL — a shader can only shade drawn pixels), and hardware bitmaps forbid CPU
 * reads by design, so readback-free detection on the bitmap is infeasible. The smallest
 * possible downsample analysed off the main thread is the whole detection cost: ~14 K pixels
 * and one 96 px one-time GPU→CPU copy per page, traced as `absx.cropDetect` for the lead.
 */
data class CropRect(
    val left: Int,
    val top: Int,
    /** Exclusive, like Rect.right. */
    val right: Int,
    /** Exclusive, like Rect.bottom. */
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /**
     * Maps this rect from a [srcW]×[srcH] image into [dstW]×[dstH]. Edges round conservatively
     * (crop less, never more): inclusive edges floor, exclusive edges ceil.
     */
    fun scaleFrom(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect = CropRect(
        left = left * dstW / srcW,
        top = top * dstH / srcH,
        right = ceilDiv(right * dstW, srcW),
        bottom = ceilDiv(bottom * dstH, srcH),
    )

    companion object {
        /** Full-image rect: the no-crop value. */
        fun full(width: Int, height: Int): CropRect = CropRect(0, 0, width, height)
    }
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

object CropMath {

    /**
     * Benchmark intent extra ([com.absolutex.gpu.CROP]): `"0"` disables cropping, anything else
     * (including absent) leaves the [cropEnabled] parameter in charge. Separate extra per
     * milestone-3 precedent, so each codec stays total on its own.
     */
    const val EXTRA_CROP = "com.absolutex.gpu.CROP"

    /** Thumbnail longest edge for detection. 96 px keeps a 2000 px page's error near ±20 px. */
    const val THUMB_EDGE = 96

    /** Per-channel distance from the edge colour that still counts as margin (JPEG noise). */
    const val TOLERANCE = 16

    /** Fraction of an edge row/column that must match before it counts as margin. */
    const val COVERAGE = 0.92f

    /** Strips below this (thumbnail px) are binding glue or rounding, not margins. */
    const val MIN_MARGIN_PX = 2

    /** No single side gives up more than this fraction of the image, whatever it looks like. */
    const val MAX_SIDE_FRACTION = 0.25f

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF

    /**
     * Margin rect in [pixels] coordinates, or null when nothing should be cropped: full-bleed,
     * sub-threshold strips, or a detection that ran to its cap on every side — a uniform page
     * reads as margin everywhere, and no content edge means no crop.
     */
    fun detect(pixels: IntArray, width: Int, height: Int): CropRect? {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return null
        val edge = edgeColour(pixels, width, height)
        val maxTop = (height * MAX_SIDE_FRACTION).toInt()
        val maxBottom = (height * MAX_SIDE_FRACTION).toInt()
        val maxLeft = (width * MAX_SIDE_FRACTION).toInt()
        val maxRight = (width * MAX_SIDE_FRACTION).toInt()
        val top = scanRows(pixels, width, height, 0, 1, maxTop, edge).withoutGlue()
        val bottom = scanRows(pixels, width, height, height - 1, -1, maxBottom, edge).withoutGlue()
        val left = scanCols(pixels, width, height, 0, 1, maxLeft, edge).withoutGlue()
        val right = scanCols(pixels, width, height, width - 1, -1, maxRight, edge).withoutGlue()
        if (listOf(top, bottom, left, right).all { it == 0 }) return null
        val caps = listOf(top to maxTop, bottom to maxBottom, left to maxLeft, right to maxRight)
        if (caps.all { (strips, limit) -> strips >= limit }) return null
        return CropRect(left, top, width - right, height - bottom)
    }

    /** Strips below the minimum are binding glue or rounding, not margins. */
    private fun Int.withoutGlue(): Int = if (this < MIN_MARGIN_PX) 0 else this

    /** Mean RGB of the four 2×2 corner blocks: the scan margin colour, whatever it is. */
    private fun edgeColour(pixels: IntArray, width: Int, height: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        // Block origins clamped inside the image: a bottom block grows upward, never past
        // the last row.
        val ox = listOf(0, maxOf(0, width - 2))
        val oy = listOf(0, maxOf(0, height - 2))
        for (by in oy) {
            for (bx in ox) {
                for (dy in 0 until min(2, height)) {
                    for (dx in 0 until min(2, width)) {
                        val c = pixels[(by + dy) * width + bx + dx]
                        r += c shr RED_SHIFT and CHANNEL_MASK
                        g += c shr GREEN_SHIFT and CHANNEL_MASK
                        b += c and CHANNEL_MASK
                        n++
                    }
                }
            }
        }
        return (r / n) shl RED_SHIFT or ((g / n) shl GREEN_SHIFT) or (b / n)
    }

    private fun matches(pixel: Int, edge: Int): Boolean {
        // Parentheses are load-bearing: infix `and` binds looser than `-`, so the channels
        // must be masked before they are subtracted.
        if (channelDiff(pixel, edge, RED_SHIFT) > TOLERANCE) return false
        if (channelDiff(pixel, edge, GREEN_SHIFT) > TOLERANCE) return false
        return channelDiff(pixel, edge, 0) <= TOLERANCE
    }

    private fun channelDiff(pixel: Int, edge: Int, shift: Int): Int =
        abs((pixel shr shift and CHANNEL_MASK) - (edge shr shift and CHANNEL_MASK))

    private fun abs(value: Int): Int = if (value < 0) -value else value

    /** Strips from one edge: counts inward while row coverage holds, capped at [limit]. */
    private fun scanRows(pixels: IntArray, width: Int, height: Int, start: Int, step: Int, limit: Int, edge: Int): Int {
        var strips = 0
        var row = start
        while (strips < limit && row in 0 until height) {
            var hits = 0
            for (x in 0 until width) {
                if (matches(pixels[row * width + x], edge)) hits++
            }
            if (hits < width * COVERAGE) break
            strips++
            row += step
        }
        return strips
    }

    private fun scanCols(pixels: IntArray, width: Int, height: Int, start: Int, step: Int, limit: Int, edge: Int): Int {
        var strips = 0
        var col = start
        while (strips < limit && col in 0 until width) {
            var hits = 0
            for (y in 0 until height) {
                if (matches(pixels[y * width + col], edge)) hits++
            }
            if (hits < height * COVERAGE) break
            strips++
            col += step
        }
        return strips
    }
}
