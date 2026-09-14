package com.absolutex.source.pdf

import kotlin.math.ceil

/**
 * Tile geometry. Pure integer/float maths with no Android types, so it is exercised by plain
 * JVM unit tests rather than only on a device.
 */
internal object PdfTiling {

    /**
     * Pixel extent of one page axis at [scale] device pixels per point.
     *
     * Rounds up, never to nearest: every caller that lays out a tile grid must agree with
     * every caller that renders into it, and rounding down would leave the last row or
     * column of pixels with no tile covering it.
     */
    fun scaledLength(points: Float, scale: Float): Int {
        require(points > 0f) { "page extent must be positive, was $points" }
        require(scale > 0f && scale.isFinite()) { "scale must be positive and finite, was $scale" }
        return ceil(points.toDouble() * scale.toDouble()).toInt().coerceAtLeast(1)
    }

    /**
     * Fails fast on a tile that could not produce a sensible bitmap. An empty or inverted
     * rect would otherwise reach Bitmap.createBitmap as a zero/negative dimension, where the
     * error names the bitmap rather than the caller's rect.
     */
    fun requireUsableTile(left: Int, top: Int, right: Int, bottom: Int) {
        require(right > left && bottom > top) {
            "tile must be non-empty, was ($left, $top, $right, $bottom)"
        }
    }
}
