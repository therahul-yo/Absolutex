package com.absolutex.feature.reader

import com.absolutex.model.FitGeometry
import com.absolutex.model.FitMode
import kotlin.math.max
import kotlin.math.min

/**
 * The base layer's longest edge, as a multiple of the screen's. Full size on a 12000px page would
 * otherwise be a texture of hundreds of MB; tiles cover the detail the cap leaves out.
 */
internal const val MAX_BASE_EDGE = 1.25f

/**
 * Size the base layer decodes at: what the page is drawn at for [fitMode] at zoom 1, never above
 * source resolution, capped by [MAX_BASE_EDGE]. Decoding at the viewport box instead left fit
 * height and full size upscaled from a smaller bitmap and permanently soft, since tiles only
 * start once the user zooms.
 *
 * [contentW] x [contentH] is the size the page is fitted at: the crop's, or the page's own when
 * uncropped. The decoded bitmap is always the whole page ([pageW] x [pageH]) scaled by one factor,
 * so crop moves this size and nothing else: the bitmap's pixels are the same cropped or not, and
 * crop is applied when it is drawn. That is why a crop toggle needs no cache flush, only a new
 * [BaseKey] where the size differs (see `BaseTargetTest`).
 */
@Suppress("LongParameterList")
internal fun baseTargetSize(
    fitMode: FitMode,
    viewportW: Int,
    viewportH: Int,
    pageW: Int,
    pageH: Int,
    contentW: Int,
    contentH: Int,
    maxBaseWidth: Int?,
): Pair<Int, Int> {
    val fitScale = FitGeometry.baseScale(fitMode, viewportW, viewportH, contentW, contentH)
    val capped = min(fitScale, 1f)
    val longest = max(pageW, pageH) * capped
    val cap = MAX_BASE_EDGE * max(viewportW, viewportH)
    val fit = capped * if (longest > cap) cap / longest else 1f
    val k = maxBaseWidth?.let { min(fit, it.toFloat() / pageW) } ?: fit
    return max(1, (pageW * k).toInt()) to max(1, (pageH * k).toInt())
}
