package com.absolutex.feature.reader

import android.os.Trace
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.gpu.BackgroundMath
import com.absolutex.core.gpu.CropMath
import com.absolutex.core.gpu.CropRect
import kotlinx.coroutines.withContext

/**
 * Whether a page is decoded only to sample its edge colour: the automatic background is on but
 * crop is not. Crop detection and the colour read the same thumbnail (crop on does both in one
 * pass); with both off nothing is decoded. Crop off no longer implies the background is off: the
 * two settings are independent.
 */
internal fun edgeSampleOnly(cropActive: Boolean, autoBackground: Boolean): Boolean =
    !cropActive && autoBackground

/**
 * What one pass over the analysis thumbnail found, in thumbnail pixels: the crop (null when crop
 * detection did not run or found none) and the page's mean edge colour as opaque ARGB.
 */
internal class PageEdges(val crop: CropRect?, val background: Int)

/**
 * Reads [pixels] once: detects the crop only when [detectCrop], and samples the edge colour of the
 * crop when there is one and of the whole page otherwise. Pure, so the "no crop, still a
 * background" case is a JVM test rather than a device check.
 */
internal fun readPageEdges(pixels: IntArray, width: Int, height: Int, detectCrop: Boolean): PageEdges {
    val crop = if (detectCrop) CropMath.detect(pixels, width, height) else null
    return PageEdges(crop, BackgroundMath.sampleEdge(pixels, width, height, crop))
}

/**
 * Decodes the analysis thumbnail off the main thread and reads it. The crop is scaled to the full
 * page; null for the whole result when the decode failed, so the caller keeps its last colour
 * rather than flashing to black. No hardware bitmap is touched: the thumbnail is software and is
 * recycled here.
 */
internal suspend fun analysePage(page: PageImage, detectCrop: Boolean): PageEdges? =
    withContext(DecodeDispatchers.decode) {
        val thumb = runCatching { page.decodeThumbnail(CropMath.THUMB_EDGE) }.getOrNull()
            ?: return@withContext null
        val sw = thumb.width
        val sh = thumb.height
        val pixels = IntArray(sw * sh)
        Trace.beginSection("absx.cropDetect")
        try {
            thumb.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val edges = readPageEdges(pixels, sw, sh, detectCrop)
            PageEdges(edges.crop?.scaleFrom(sw, sh, page.width, page.height), edges.background)
        } finally {
            Trace.endSection()
            thumb.recycle()
        }
    }
