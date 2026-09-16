package com.absolutex.core.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * One page as the reader draws it: a size in source pixels, a whole-page base layer at a chosen
 * size, and tiles of that source grid at a subsample. Scans decode these from bytes; a rendered
 * format (PDF) draws them, with a source resolution it picks for itself.
 */
interface PageImage : Closeable {
    val width: Int
    val height: Int

    /** Whole page fit inside the target box, never above source resolution. */
    fun decodeBase(targetWidth: Int, targetHeight: Int): Bitmap

    /** One tile at its own subsample, or null when it cannot be produced. */
    fun decodeTile(tile: Tile): Bitmap?

    companion object {
        /** Scales (w,h) to fit inside the target box, preserving aspect ratio. */
        fun fitInside(w: Int, h: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0 || boxW <= 0 || boxH <= 0) return w to h
            val scale = minOf(boxW.toFloat() / w, boxH.toFloat() / h)
            // Never upscale at decode time: enlarging is the GPU's job (§4), and decoding
            // above source resolution burns memory for no additional detail.
            if (scale >= 1f) return w to h
            return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
        }

        /**
         * Never throws for corrupt input: if bounds decode to width<=0||height<=0 the
         * returned object is corrupt and callers must treat it as unreadable
         * (check width/height, do not cache), but [from] itself still returns.
         */
        fun from(bytes: ByteArray): PageImage {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val rd = runCatching {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            }.getOrNull()
            return EncodedPageImage(bytes, bounds.outWidth, bounds.outHeight, rd)
        }
    }
}

/**
 * One page's compressed bytes plus the two decoders the reader needs.
 *
 * The compressed bytes stay resident (about 1 MB for a real scan) because both the base layer
 * and every tile decode from them. Re-extracting from the archive per tile would put archive
 * I/O inside the zoom interaction, which the §3 budget cannot absorb.
 */
private class EncodedPageImage(
    private val bytes: ByteArray,
    override val width: Int,
    override val height: Int,
    private val regionDecoder: BitmapRegionDecoder?,
) : PageImage {

    /**
     * Whole page at display size. `setTargetSize` decodes straight to the size we need —
     * decoding full-res and downscaling would cost ~24 MB and a copy per page (§3).
     */
    override fun decodeBase(targetWidth: Int, targetHeight: Int): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val (w, h) = PageImage.fitInside(info.size.width, info.size.height, targetWidth, targetHeight)
            decoder.setTargetSize(w, h)
            // Hardware bitmaps are the default allocation path (§1). They are immutable and
            // cannot be read back, which is exactly why colour correction is an AGSL shader at
            // draw time rather than a pixel edit.
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            decoder.isMutableRequired = false
        }
    }

    /** Returns null if the region decoder is unavailable. */
    override fun decodeTile(tile: Tile): Bitmap? {
        val rd = regionDecoder ?: return null
        // BitmapRegionDecoder is not thread-safe; tiles decode on a pool while the
        // base layer may decode concurrently, so all region access takes one monitor.
        synchronized(this) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = tile.sampleSize
                inPreferredConfig = Bitmap.Config.HARDWARE
            }
            val rect = android.graphics.Rect(tile.left, tile.top, tile.right, tile.bottom)
            return runCatching { rd.decodeRegion(rect, opts) }.getOrElse {
                // Some encoders reject HARDWARE for region decode. Fall back to ARGB_8888 —
                // never RGB_565, which the brief forbids outright.
                runCatching {
                    rd.decodeRegion(rect, BitmapFactory.Options().apply {
                        inSampleSize = tile.sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }.getOrNull()
            }
        }
    }

    override fun close() {
        regionDecoder?.recycle()
    }
}
