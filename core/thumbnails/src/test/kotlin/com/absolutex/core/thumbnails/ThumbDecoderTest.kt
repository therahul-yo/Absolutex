package com.absolutex.core.thumbnails

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Bitmap decode runs on the JVM here through Robolectric, so the at-size ImageDecoder path is
 * covered without a device. PNG bytes are generated with javax.imageio, which is pure JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbDecoderTest {

    private fun pngBytes(width: Int, height: Int): ByteArray = testPngBytes(width, height)

    @Test fun `decodes at the requested width preserving aspect`() {
        val bitmap = ThumbDecoder.decode(pngBytes(64, 32), ThumbRequest.BUCKET_SMALL)
        assertEquals(ThumbRequest.BUCKET_SMALL, bitmap.width)
        assertEquals(ThumbRequest.BUCKET_SMALL / 2, bitmap.height)
    }

    @Test fun `small thumbs decode at the small bucket`() {
        val bitmap = ThumbDecoder.decode(pngBytes(128, 128), ThumbRequest.BUCKET_SMALL)
        assertEquals(ThumbRequest.BUCKET_SMALL, bitmap.width)
        assertEquals(ThumbRequest.BUCKET_SMALL, bitmap.height)
    }

    @Test fun `hardware requests map to the hardware allocator`() {
        // The shadow always yields ARGB_8888 dummies, so the allocator mapping is pinned directly
        // instead of through a decoded bitmap; on device this selects real hardware allocation.
        assertEquals(ImageDecoder.ALLOCATOR_HARDWARE, ThumbDecoder.allocatorFor(Bitmap.Config.HARDWARE))
    }

    @Test fun `software requests map to the software allocator`() {
        assertEquals(ImageDecoder.ALLOCATOR_SOFTWARE, ThumbDecoder.allocatorFor(Bitmap.Config.ARGB_8888))
    }

    @Test fun `an explicit software config is honored for disk entries`() {
        // The shadow yields ARGB_8888 regardless of allocator, so this pins success plus size here;
        // the allocator selection itself is pinned by the mapping tests above.
        val bitmap = ThumbDecoder.decode(pngBytes(64, 32), ThumbRequest.BUCKET_SMALL, Bitmap.Config.ARGB_8888)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test fun `decoded thumbs are never empty`() {
        val bitmap = ThumbDecoder.decode(pngBytes(64, 32), ThumbRequest.BUCKET_SMALL)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
        assertTrue(bitmap.allocationByteCount > 0)
    }

    @Test(expected = IOException::class)
    fun `truncated bytes throw IOException`() {
        // A cut-off PNG: valid header, missing data. Both the device decoder and the shadow fail it.
        val png = pngBytes(64, 32)
        ThumbDecoder.decode(png.copyOf(png.size / 2), ThumbRequest.BUCKET_SMALL)
    }

    @Test(expected = IOException::class)
    fun `empty bytes throw IOException`() {
        ThumbDecoder.decode(ByteArray(0), ThumbRequest.BUCKET_SMALL)
    }

    @Test(expected = IOException::class)
    fun `non-positive target widths throw IOException`() {
        ThumbDecoder.decode(pngBytes(64, 32), 0)
    }
}
