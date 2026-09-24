package com.absolutex.feature.reader

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [uploadForDraw]'s contract: display pixels reach the frame as HARDWARE so the upload
 * happens on the decode thread, the software original is never held twice, and a device that
 * cannot allocate hardware bitmaps still gets its tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfUploadForDrawTest {

    private fun softwareTile(): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { setPixel(3, 4, Color.RED) }

    @Test fun `a software tile becomes an immutable hardware bitmap with its pixels intact`() {
        val uploaded = softwareTile().uploadForDraw()

        assertEquals(Bitmap.Config.HARDWARE, uploaded.config)
        assertEquals(8, uploaded.width)
        assertEquals(8, uploaded.height)
        assertEquals(Color.RED, uploaded.getPixel(3, 4))
        assertFalse(uploaded.isMutable)
    }

    @Test fun `the software original is recycled so a page is never held twice`() {
        val software = softwareTile()

        software.uploadForDraw()

        assertTrue(software.isRecycled)
    }

    @Test fun `an already-hardware bitmap is returned as is, never recycled`() {
        val hardware = softwareTile().uploadForDraw()

        assertSame(hardware, hardware.uploadForDraw())
        assertFalse(hardware.isRecycled)
    }

    @Test fun `a failed hardware copy falls back to the untouched software original`() {
        val software = softwareTile()

        assertSame(software, software.uploadForDraw { null })
        assertFalse(software.isRecycled)
    }

    @Test fun `a throwing hardware copy falls back to the untouched software original`() {
        val software = softwareTile()

        assertSame(software, software.uploadForDraw { error("no GPU allocator") })
        assertFalse(software.isRecycled)
    }
}
