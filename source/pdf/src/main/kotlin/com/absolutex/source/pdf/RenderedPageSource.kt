package com.absolutex.source.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable

/**
 * A container whose pages are *rendered on demand* rather than read out as encoded images.
 *
 * This is deliberately NOT `com.absolutex.source.ComicSource`. That interface hands the
 * caller an `InputStream` per page, which assumes the page already exists as a compressed
 * bitmap inside the container — true for cbz/cbr/cb7/cbt, false for PDF, where a page is a
 * display list with no fixed resolution and no byte range to hand out. Forcing PDF through
 * `openPage(): InputStream` would mean rasterising a whole page to PNG in memory just to
 * decode it straight back, at a resolution nothing has chosen yet.
 *
 * TODO(source-api): this interface belongs in :source:api next to ComicSource, as the
 * rendered-source counterpart, so the reader can hold either behind one type. Doing that
 * needs two things from files this lane does not own:
 *   1. A shared supertype in `source/api/ComicSource.kt` — something like
 *      `interface PageContainer : Closeable { val pageCount: Int }` that both
 *      `ComicSource` and this interface extend, so :feature:reader can accept either.
 *   2. `core/model/Types.kt`'s `Page` currently requires a non-null `entryName`, which a PDF
 *      page does not have. Either make `entryName` nullable, or add a
 *      `RenderedPage(index, widthPoints, heightPoints)` alongside it.
 * Until both land, :source:pdf keeps its own types and does not depend on :source:api.
 */
interface RenderedPageSource : Closeable {
    val pageCount: Int

    /** Intrinsic page size in PostScript points (1/72"). */
    fun pageSize(pageIndex: Int): PdfPageSize

    /**
     * Renders one tile of a page.
     *
     * [scale] is device pixels per point, so 1.0 is 72 dpi and the full page at that scale
     * measures [PdfPageSize.width] x [PdfPageSize.height] pixels. [tile] selects a region of
     * that scaled page; the returned bitmap is exactly [Rect.width] x [Rect.height] and is
     * always ARGB_8888. Regions extending past the page edge come back white rather than
     * clipped, so callers get a uniform tile grid.
     */
    fun renderTile(pageIndex: Int, tile: Rect, scale: Float, flags: Int = 0): Bitmap

    /** Empty when the document declares no outline. */
    fun outline(): List<PdfOutlineEntry>
}

/** Page dimensions in PostScript points. */
data class PdfPageSize(val width: Float, val height: Float)
