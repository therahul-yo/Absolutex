package com.absolutex.model

/**
 * Where a page sits on screen for a [FitMode] (§5.2). Pure, so the reader's draw, tile fetch,
 * gesture clamp and base-layer decode all agree on one answer instead of four copies of it.
 *
 * Coordinates follow the reader's convention: the page is centred in the viewport, then moved by
 * an offset. An offset of +[maxOffsetY] puts the page's top edge on the screen's top edge.
 */
object FitGeometry {

    /** Screen pixels per page pixel at zoom 1. */
    fun baseScale(mode: FitMode, viewportW: Int, viewportH: Int, pageW: Int, pageH: Int): Float {
        if (minOf(viewportW, viewportH, pageW, pageH) <= 0) return 1f
        val byWidth = viewportW.toFloat() / pageW
        val byHeight = viewportH.toFloat() / pageH
        return when (mode) {
            FitMode.FIT_SCREEN -> minOf(byWidth, byHeight)
            FitMode.FIT_WIDTH -> byWidth
            FitMode.FIT_HEIGHT -> byHeight
            FitMode.FULL_SIZE -> 1f
        }
    }

    /** How far the page can move sideways at [scale] screen px per page px; 0 when it fits. */
    fun maxOffsetX(viewportW: Int, pageW: Int, scale: Float): Float = overflow(viewportW, pageW, scale)

    /** How far the page can move vertically at [scale] screen px per page px; 0 when it fits. */
    fun maxOffsetY(viewportH: Int, pageH: Int, scale: Float): Float = overflow(viewportH, pageH, scale)

    /**
     * Where reading starts on a page that overflows: its top, and the edge a reader of that flow
     * starts from — the right edge of a right-to-left spread, the left edge otherwise.
     */
    fun startOffsetX(viewportW: Int, pageW: Int, scale: Float, rightToLeft: Boolean): Float {
        val max = maxOffsetX(viewportW, pageW, scale)
        return if (rightToLeft) -max else max
    }

    fun startOffsetY(viewportH: Int, pageH: Int, scale: Float): Float = maxOffsetY(viewportH, pageH, scale)

    /**
     * Below half a pixel is rounding, not overflow: 1988 × (1240 / 1988) lands a hair above 1240, and
     * treating that as scrollable would lock the pager on a page that fits and swallow every swipe.
     */
    private fun overflow(viewport: Int, page: Int, scale: Float): Float {
        val half = (page * scale - viewport) / 2f
        return if (half < SUBPIXEL) 0f else half
    }

    private const val SUBPIXEL = 0.5f
}
