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
        return document.renderTile(pageIndex, Rect(0, 0, w, h), SCALE * w / width).uploadForDraw()
    }

    override fun decodeTile(tile: Tile): Bitmap? {
        val n = tile.sampleSize
        val region = Rect(tile.left / n, tile.top / n, ceilDiv(tile.right, n), ceilDiv(tile.bottom, n))
        return runCatching { document.renderTile(pageIndex, region, SCALE / n).uploadForDraw() }.getOrNull()
    }

    /** Crop-detection thumbnail: PDFium renders to a software bitmap, so no readback stall. */
    override fun decodeThumbnail(targetEdge: Int): Bitmap? {
        // Deliberately NOT uploadForDraw: the crop path reads these pixels back with getPixels,
        // which a HARDWARE bitmap cannot serve without a GPU stall (or at all). Display pixels
        // upload once; thumbnails are read back every time.
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

/**
 * Hands a freshly rendered PDF tile to the GPU on the calling thread, returning what to draw.
 *
 * PDFium renders into CPU memory — the JNI layer can only `lockPixels` a mutable software
 * bitmap, so unlike the archive path (which decodes straight to `ALLOCATOR_HARDWARE`) every
 * PDF tile starts life as software. Drawing software pixels uploads them on the frame that
 * first needs them, which is what `dumpsys gfxinfo` counts as a slow bitmap upload and what
 * janks page turns. Copying to `HARDWARE` here instead performs that same upload on the
 * decode thread that just rendered the tile, so the frame only binds an already resident
 * texture; [Bitmap.prepareToDraw] primes it before the tile reaches the base cache or tile
 * cache. Takes ownership of [this]: the software original is recycled once the copy lands,
 * since a base layer is several MB and holding both would double every PDF page.
 *
 * Two paths deliberately bypass this: [PdfDocument.renderTileInto] requires a mutable
 * software bitmap for the caller's pool, and thumbnails stay software because the crop path
 * reads them back. When the hardware copy fails the software original is returned untouched —
 * a slow upload beats no tile.
 *
 * @param copyToHardware the allocation itself, a parameter only so tests can fail it on the
 *   JVM where every allocation succeeds; callers always take the default.
 */
internal fun Bitmap.uploadForDraw(
    copyToHardware: (Bitmap) -> Bitmap? = { source -> source.copy(Bitmap.Config.HARDWARE, false) },
): Bitmap {
    if (config == Bitmap.Config.HARDWARE) {
        prepareToDraw()
        return this
    }
    val hardware = runCatching { copyToHardware(this) }.getOrNull() ?: return this
    recycle()
    hardware.prepareToDraw()
    return hardware
}
