package com.absolutex.core.gpu

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the composed local matrix against [Matrix.mapRect] — the actual Android API the GPU
 * uses — so a regression in the matrix direction or composition order fails here, not on a
 * device. The pure [ContentMatrixTest] pins the numbers; this test pins what the GPU does
 * with them.
 *
 * The composition mirrors [ColourPipeline.paintFor]: `setScale` then `postTranslate`, which
 * builds `M = T · S` (scale first, then shift). Sampling applies `M⁻¹`, so a canvas fragment
 * maps to bitmap `(frag - origin) * (bitmap / dst)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentMatrixRobolectricTest {

    /**
     * Compose the local matrix exactly as [ColourPipeline.paintFor] does, then verify that
     * mapping the bitmap's full rect through it lands on the destination rect.
     */
    private fun assertMapsToDst(bitmapW: Int, bitmapH: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val placement = contentMatrix(0, 0, bitmapW, bitmapH, left, top, right, bottom)
        val m = Matrix()
        m.setScale(placement.scaleX, placement.scaleY)
        m.postTranslate(placement.transX, placement.transY)

        val src = RectF(0f, 0f, bitmapW.toFloat(), bitmapH.toFloat())
        val mapped = RectF(src)
        m.mapRect(mapped)

        val expected = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        assertEquals("mapped rect for ${bitmapW}x${bitmapH} at ($left,$top)-($right,$bottom)",
            expected, mapped)
    }

    @Test
    fun `same size at origin maps to itself`() {
        assertMapsToDst(100, 200, 0, 0, 100, 200)
    }

    @Test
    fun `offset destination shifts the bitmap rect`() {
        assertMapsToDst(100, 200, 30, 40, 130, 240)
    }

    @Test
    fun `upscaled draw maps bitmap to the larger rect`() {
        // 100×100 bitmap drawn at 200×200: the matrix scales by 2, so bitmap (0,0)-(100,100)
        // maps to canvas (0,0)-(200,200).
        assertMapsToDst(100, 100, 0, 0, 200, 200)
    }

    @Test
    fun `downscaled draw maps bitmap to the smaller rect`() {
        // 200×200 bitmap drawn at 100×100: the matrix scales by 0.5, so bitmap (0,0)-(200,200)
        // maps to canvas (0,0)-(100,100).
        assertMapsToDst(200, 200, 0, 0, 100, 100)
    }

    @Test
    fun `upscaled with offset maps correctly`() {
        // 100×100 bitmap at (50, 60)-(250, 260): scale 2, translate (50, 60).
        assertMapsToDst(100, 100, 50, 60, 250, 260)
    }

    @Test
    fun `downscaled with offset maps correctly`() {
        // 200×200 bitmap at (10, 20)-(110, 120): scale 0.5, translate (10, 20).
        assertMapsToDst(200, 200, 10, 20, 110, 120)
    }
}
