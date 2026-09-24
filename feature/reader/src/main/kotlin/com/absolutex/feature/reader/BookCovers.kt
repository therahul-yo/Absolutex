package com.absolutex.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.absolutex.core.data.runCatchingCancellable
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.core.thumbnails.ThumbRequest
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import com.absolutex.source.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover art for the library: a book's cover, opened only when no cache already holds it.
 *
 * Its own cache directory, not the reader's. The reader's pipeline is closed and rebuilt per book,
 * and two pipelines on one directory would race on its disk journal; a cover is also a different
 * product — one image per book at grid size — from the strip's page thumbnails.
 *
 * A PDF has no encoded page bytes to cache, so its first page is rendered once, encoded, and then
 * cached like any other cover. Most libraries are mostly PDFs; re-rendering each one every time it
 * scrolls into view is the cost this avoids.
 */
@Singleton
class BookCovers @Inject constructor(@ApplicationContext private val context: Context) {
    private val thumbs = ThumbPipelineHolder(File(context.cacheDir, COVER_DIR))

    /** Books are opened a few at a time: a fast fling must not open thirty archives at once. */
    private val opens = Semaphore(MAX_CONCURRENT_OPENS)

    /**
     * The cover of the book at [path], or null when it cannot be read. [key] must change when the
     * file does — a replaced file must not keep its predecessor's cover.
     */
    suspend fun cover(path: String, key: String, widthPx: Int): Bitmap? {
        val request = ThumbRequest(key, 0, ThumbRequest.snapWidth(widthPx))
        val pipeline = thumbs.get()
        pipeline.cached(request)?.let { return it }
        return opens.withPermit {
            runCatchingCancellable {
                val bytes = withContext(DecodeDispatchers.extract) {
                    context.openBook(bookUri(path)).use { coverBytes(it, widthPx) }
                }
                pipeline.load(OneImage(bytes), request)
            }.getOrNull()
        }
    }

    private fun coverBytes(book: Closeable, widthPx: Int): ByteArray = when (book) {
        is PdfDocument -> {
            val page = PdfPageImage.open(book, 0).decodeBase(widthPx * PDF_OVERSAMPLE, widthPx * PDF_OVERSAMPLE * 2)
            ByteArrayOutputStream().use { out ->
                page.compress(Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, out)
                page.recycle()
                out.toByteArray()
            }
        }
        is ComicSource -> book.openCover().use(InputStream::readBytes)
        else -> throw java.io.IOException("not a book")
    }

    /** One already-read image, shaped as a book so the pipeline caches it like any other. */
    private class OneImage(private val bytes: ByteArray) : ComicSource {
        override val pages = listOf(Page(0, "cover"))
        override fun openPage(index: Int): InputStream = ByteArrayInputStream(bytes)
        override fun close() = Unit
    }

    private companion object {
        const val COVER_DIR = "covers"
        const val MAX_CONCURRENT_OPENS = 3
        const val PDF_OVERSAMPLE = 2
        const val PDF_JPEG_QUALITY = 90
    }
}

private fun bookUri(path: String): Uri =
    if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
