package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Byte array to [Bitmap] at thumbnail size.
 *
 * Decodes straight at the target size via `setTargetSize`: a full-res decode plus downscale would
 * transiently hold the whole page for a 256px result. Corrupt input throws [IOException] and the
 * pipeline never caches the failure, so a retry re-reads the source.
 */
object ThumbDecoder {

    fun decode(
        bytes: ByteArray,
        targetWidth: Int,
        config: Bitmap.Config = Bitmap.Config.HARDWARE,
    ): Bitmap {
        val bounds = boundsOf(bytes)
        val targetHeight = scaledHeight(bounds.first, bounds.second, targetWidth)
        return decodeAt(bytes, targetWidth, targetHeight, config)
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.isEmpty()) throw IOException("Empty image bytes")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The Robolectric shadow raises RuntimeException for truncated input where the device reports
        // -1 dimensions; both collapse to the dimension check below and surface as IOException.
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Undecodable image bytes")
        return options.outWidth to options.outHeight
    }

    private fun scaledHeight(srcWidth: Int, srcHeight: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) throw IOException("Non-positive target width")
        return maxOf(1, (srcHeight.toLong() * targetWidth / srcWidth).toInt())
    }

    private fun decodeAt(bytes: ByteArray, targetWidth: Int, targetHeight: Int, config: Bitmap.Config): Bitmap {
        // runCatching normalizes the shadow's RuntimeException(ImageIO) and the device's
        // DecodeException(IOException) alike; both surface below as IOException.
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(targetWidth, targetHeight)
                decoder.allocator = allocatorFor(config)
                decoder.isMutableRequired = false
            }
        }.getOrElse { e -> throw asIOException(e) }
    }

    private fun asIOException(e: Throwable): IOException =
        if (e is IOException) e else IOException("Thumbnail decode failed", e)

    internal fun allocatorFor(config: Bitmap.Config): Int =
        if (config == Bitmap.Config.HARDWARE) ImageDecoder.ALLOCATOR_HARDWARE else ImageDecoder.ALLOCATOR_SOFTWARE
}
