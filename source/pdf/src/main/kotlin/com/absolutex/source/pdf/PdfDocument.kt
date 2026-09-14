package com.absolutex.source.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A PDF opened through PDFium.
 *
 * Takes a [ParcelFileDescriptor] because SAF is the default access path and only ever hands
 * out an fd. Note this takes the descriptor itself, not an open-per-read factory the way
 * `LibArchiveSource` does. That difference is deliberate: opening a PDF parses the xref
 * table and builds the object map, so re-opening per read would put that cost on every tile.
 * pdfium_jni.c explains why holding one descriptor is safe here even for a SAF fd.
 *
 * Why not the platform `android.graphics.pdf.PdfRenderer`: it renders a whole page into a
 * whole bitmap. There is no way to ask it for one tile of a zoomed page, so a 4x zoom would
 * mean rasterising the entire page at 4x before cropping — several hundred MB for a large
 * page, on a reader whose budget is one page-turn. It also exposes no outline and no control
 * over render DPI. See the brief, section 2.
 *
 * Thread-safety: safe to call from several threads. Note that PDFium itself is serialised
 * process-wide (pdfium_jni.c explains why), so concurrent [renderTile] calls queue rather
 * than run in parallel — correct, but not yet fast. The lock here is a separate concern: it
 * only stops [close] from freeing the document underneath an in-flight render.
 */
class PdfDocument private constructor(
    private val pfd: ParcelFileDescriptor,
    private var handle: Long,
    override val pageCount: Int,
) : RenderedPageSource {

    private val lifecycle = ReentrantReadWriteLock()
    private var closed = false

    private inline fun <T> withHandle(block: (Long) -> T): T = lifecycle.read {
        if (closed) throw IllegalStateException("PdfDocument is closed")
        block(handle)
    }

    override fun pageSize(pageIndex: Int): PdfPageSize {
        requirePageInRange(pageIndex)
        val out = FloatArray(2)
        val ok = withHandle { Pdfium.nativePageSize(it, pageIndex, out) }
        if (!ok) throw IOException("unreadable page $pageIndex")
        return PdfPageSize(out[0], out[1])
    }

    override fun renderTile(pageIndex: Int, tile: Rect, scale: Float, flags: Int): Bitmap {
        PdfTiling.requireUsableTile(tile.left, tile.top, tile.right, tile.bottom)
        val bitmap = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888)
        renderTileInto(pageIndex, tile, scale, bitmap, flags)
        return bitmap
    }

    /**
     * [renderTile] into a caller-owned bitmap.
     *
     * A tiled reader re-renders the same grid on every zoom step, so allocating a fresh
     * bitmap per tile would churn several MB per gesture. This lets the caller keep a pool.
     * The bitmap must be mutable ARGB_8888 and exactly the size of [tile].
     */
    fun renderTileInto(
        pageIndex: Int,
        tile: Rect,
        scale: Float,
        bitmap: Bitmap,
        flags: Int = 0,
    ) {
        requirePageInRange(pageIndex)
        PdfTiling.requireUsableTile(tile.left, tile.top, tile.right, tile.bottom)
        require(bitmap.isMutable) { "tile bitmap must be mutable" }
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "tile bitmap must be ARGB_8888, was ${bitmap.config}"
        }
        require(bitmap.width == tile.width() && bitmap.height == tile.height()) {
            "tile bitmap is ${bitmap.width}x${bitmap.height}, tile is ${tile.width()}x${tile.height()}"
        }

        val size = pageSize(pageIndex)
        val scaledWidth = PdfTiling.scaledLength(size.width, scale)
        val scaledHeight = PdfTiling.scaledLength(size.height, scale)

        val ok = withHandle {
            Pdfium.nativeRenderTile(
                it, pageIndex, tile.left, tile.top, scaledWidth, scaledHeight, flags, bitmap,
            )
        }
        if (!ok) throw IOException("failed to render page $pageIndex tile $tile at scale $scale")
    }

    @Suppress("UNCHECKED_CAST")
    override fun outline(): List<PdfOutlineEntry> {
        val raw = withHandle { Pdfium.nativeOutline(it) } ?: return emptyList()
        val titles = raw[0] as Array<String>
        val depths = raw[1] as IntArray
        val pages = raw[2] as IntArray
        return List(titles.size) { i ->
            PdfOutlineEntry(title = titles[i], depth = depths[i], pageIndex = pages[i])
        }
    }

    override fun close() = lifecycle.write {
        if (!closed) {
            closed = true
            Pdfium.nativeClose(handle)
            handle = 0L
            pfd.close()
        }
    }

    private fun requirePageInRange(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) {
            throw IndexOutOfBoundsException("page $pageIndex of $pageCount")
        }
    }

    companion object {
        /**
         * @throws PdfPasswordException if the document is encrypted and [password] is wrong
         *   or absent.
         * @throws PdfException for any other reason PDFium refuses the document.
         */
        fun open(pfd: ParcelFileDescriptor, password: String? = null): PdfDocument {
            val result = Pdfium.nativeOpen(pfd.fd, password)
            if (result <= 0L) throw PdfException.forCode((-result).toInt())
            val count = Pdfium.nativePageCount(result)
            if (count <= 0) {
                Pdfium.nativeClose(result)
                throw PdfException(PdfException.ERR_FORMAT, "document declares no pages")
            }
            return PdfDocument(pfd, result, count)
        }
    }
}

/** A PDFium failure, carrying the FPDF_ERR_* code so callers can branch without string checks. */
open class PdfException(val code: Int, message: String) : IOException(message) {
    companion object {
        const val ERR_UNKNOWN = 1
        const val ERR_FILE = 2
        const val ERR_FORMAT = 3
        const val ERR_PASSWORD = 4
        const val ERR_SECURITY = 5
        const val ERR_PAGE = 6

        internal fun forCode(code: Int): PdfException = when (code) {
            ERR_PASSWORD -> PdfPasswordException()
            ERR_FILE -> PdfException(code, "file could not be opened")
            ERR_FORMAT -> PdfException(code, "not a PDF, or corrupted")
            ERR_SECURITY -> PdfException(code, "unsupported security scheme")
            ERR_PAGE -> PdfException(code, "page not found or content error")
            else -> PdfException(ERR_UNKNOWN, "unknown PDFium error ($code)")
        }
    }
}

/** Encrypted document: either no password was supplied, or the one supplied was wrong. */
class PdfPasswordException : PdfException(ERR_PASSWORD, "password required or incorrect")
