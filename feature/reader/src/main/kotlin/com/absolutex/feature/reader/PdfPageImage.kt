package com.absolutex.feature.reader

import android.graphics.Bitmap
import android.graphics.Rect
import com.absolutex.core.decode.PageImage
import com.absolutex.core.decode.Tile
import com.absolutex.source.pdf.PdfDocument
import kotlin.math.ceil

/**
 * A PDF page as the reader's [PageImage]: drawn by PDFium on demand instead of decoded from bytes.
 *
 * A PDF page has no pixels of its own, so it is given some: the page is treated as a scan at
 * [SOURCE_DPI]. The tile grid, sample sizes, base-layer sizing and zoom limits then work exactly
 * as they do for a comic archive, and PageCanvas cannot tell the two apart.
 *
 * ponytail: tiles never render finer than SOURCE_DPI, so text is soft past about 4x zoom on a
 * phone. Render tiles at the on-screen scale instead when PDF text at deep zoom matters.
 */
internal class PdfPageImage private constructor(
    private val document: PdfDocument,
    private val pageIndex: Int,
    override val width: Int,
    override val height: Int,
) : PageImage {

    override fun decodeBase(targetWidth: Int, targetHeight: Int): Bitmap {
        val (w, h) = PageImage.fitInside(width, height, targetWidth, targetHeight)
        return document.renderTile(pageIndex, Rect(0, 0, w, h), SCALE * w / width)
    }

    override fun decodeTile(tile: Tile): Bitmap? {
        val n = tile.sampleSize
        val region = Rect(tile.left / n, tile.top / n, ceilDiv(tile.right, n), ceilDiv(tile.bottom, n))
        return runCatching { document.renderTile(pageIndex, region, SCALE / n) }.getOrNull()
    }

    /** Crop-detection thumbnail: PDFium renders to a software bitmap, so no readback stall. */
    override fun decodeThumbnail(targetEdge: Int): Bitmap? {
        val (w, h) = PageImage.fitInside(width, height, targetEdge, targetEdge)
        return runCatching { document.renderTile(pageIndex, Rect(0, 0, w, h), SCALE * w / width) }.getOrNull()
    }

    /** The document owns every native resource; a page holds none. */
    override fun close() = Unit

    companion object {
        /** Print resolution: an A4 page is 2480 px wide, twice a phone screen at fit width. */
        private const val SOURCE_DPI = 300f
        private const val POINTS_PER_INCH = 72f
        private const val SCALE = SOURCE_DPI / POINTS_PER_INCH

        /** Reads the page size, which is the only work opening a page does. Throws for a bad page. */
        fun open(document: PdfDocument, pageIndex: Int): PdfPageImage {
            val size = document.pageSize(pageIndex)
            return PdfPageImage(
                document, pageIndex, ceil(size.width * SCALE).toInt(), ceil(size.height * SCALE).toInt(),
            )
        }

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    }
}
