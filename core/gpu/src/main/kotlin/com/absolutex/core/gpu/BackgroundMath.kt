package com.absolutex.core.gpu

/**
 * Auto background colour (§4, §5.2, milestone 5): the page's edge colour, sampled the same way
 * on every run.
 *
 * The sample is the mean of the border strips (outer [EDGE_DEPTH] rows and columns, corners
 * counted once) of the crop region — the cropped edges when border crop is active, the whole
 * thumbnail otherwise — so the letterbox always meets the colour the page itself ends in.
 * Pure integer means make it deterministic: same pixels, same colour, no dependence on timing,
 * threads or device. PageCanvas computes it from the crop thumbnail it already holds (zero
 * extra decode) and reports it before the base layer lands, so the next page's colour is known
 * before the page settles and the background animation never flashes.
 */
object BackgroundMath {

    /** Border depth sampled per edge, in thumbnail pixels. Two rows shrug off JPEG edge ringing. */
    const val EDGE_DEPTH = 2

    /** Opaque black: the corrupt-input fallback, matching the reader's default background. */
    const val FALLBACK = 0xFF000000.toInt()

    /**
     * Mean opaque ARGB of [crop]'s border, or of the whole [width]×[height] image when [crop]
     * is null. Out-of-range crops clamp to the image; corrupt input returns [FALLBACK].
     * Never throws.
     */
    fun sampleEdge(pixels: IntArray, width: Int, height: Int, crop: CropRect?): Int {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return FALLBACK
        val full = CropRect.full(width, height)
        val region = crop?.intersect(full) ?: full
        // At least one strip per side, so even a 1 px image samples itself.
        val depth = minOf(EDGE_DEPTH, maxOf(1, region.width / 2), maxOf(1, region.height / 2))
        if (depth <= 0) return FALLBACK
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        // Top and bottom strips across the full region width.
        for (y in region.top until region.top + depth) {
            for (x in region.left until region.right) {
                val c = pixels[y * width + x]
                r += c shr RED_SHIFT and CHANNEL_MASK
                g += c shr GREEN_SHIFT and CHANNEL_MASK
                b += c and CHANNEL_MASK
                n++
            }
        }
        for (y in region.bottom - depth until region.bottom) {
            for (x in region.left until region.right) {
                val c = pixels[y * width + x]
                r += c shr RED_SHIFT and CHANNEL_MASK
                g += c shr GREEN_SHIFT and CHANNEL_MASK
                b += c and CHANNEL_MASK
                n++
            }
        }
        // Left and right strips between the corners already counted.
        for (y in region.top + depth until region.bottom - depth) {
            for (x in region.left until region.left + depth) {
                val c = pixels[y * width + x]
                r += c shr RED_SHIFT and CHANNEL_MASK
                g += c shr GREEN_SHIFT and CHANNEL_MASK
                b += c and CHANNEL_MASK
                n++
            }
            for (x in region.right - depth until region.right) {
                val c = pixels[y * width + x]
                r += c shr RED_SHIFT and CHANNEL_MASK
                g += c shr GREEN_SHIFT and CHANNEL_MASK
                b += c and CHANNEL_MASK
                n++
            }
        }
        if (n == 0L) return FALLBACK
        return (FULL_ALPHA shl ALPHA_SHIFT) or
            ((r / n).toInt() shl RED_SHIFT) or
            ((g / n).toInt() shl GREEN_SHIFT) or
            (b / n).toInt()
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val ALPHA_SHIFT = 24
    private const val CHANNEL_MASK = 0xFF
    private const val FULL_ALPHA = 0xFF
}

/** Intersection with [other], clamped inside it. Empty when they do not overlap. */
private fun CropRect.intersect(other: CropRect): CropRect = CropRect(
    left = left.coerceIn(other.left, other.right),
    top = top.coerceIn(other.top, other.bottom),
    right = right.coerceIn(other.left, other.right),
    bottom = bottom.coerceIn(other.top, other.bottom),
)
