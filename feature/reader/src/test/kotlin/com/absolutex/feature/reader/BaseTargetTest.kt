package com.absolutex.feature.reader

import com.absolutex.core.decode.TileKey
import com.absolutex.core.gpu.CropRect
import com.absolutex.model.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crop is applied when a page is drawn, not when it is decoded: the cached bitmaps are always the
 * whole page. So a crop toggle can only change a cache key through the size the base layer is
 * decoded at, and these pin that: the key moves exactly where the decoded size does, and never
 * serves a stale bitmap in between.
 */
class BaseTargetTest {

    private val pageW = 1200
    private val pageH = 1800

    // A scan with a wide white border: the crop keeps the middle 600 x 900 of a 1200 x 1800 page.
    private val crop = CropRect(left = 300, top = 450, right = 900, bottom = 1350)

    private fun target(cropped: Boolean, vw: Int = 400, vh: Int = 800, max: Int? = null) =
        baseTargetSize(
            FitMode.FIT_WIDTH, vw, vh, pageW, pageH,
            contentW = if (cropped) crop.width else pageW,
            contentH = if (cropped) crop.height else pageH,
            maxBaseWidth = max,
        )

    private fun key(target: Pair<Int, Int>) = BaseKey("book", 3, target.first, target.second)

    @Test fun `flipping crop changes the base key`() {
        // Fit width: cropped content is narrower, so the page is drawn at a larger scale.
        assertNotEquals(target(cropped = false), target(cropped = true))
        assertNotEquals(key(target(cropped = false)), key(target(cropped = true)))
    }

    @Test fun `the same crop state gives the same base key`() {
        assertEquals(key(target(cropped = true)), key(target(cropped = true)))
        assertEquals(key(target(cropped = false)), key(target(cropped = false)))
    }

    @Test fun `a cropped page decodes larger so its kept region stays sharp`() {
        val (cw, _) = target(cropped = true)
        val (uw, _) = target(cropped = false)
        assertTrue("cropped $cw vs uncropped $uw", cw > uw)
    }

    @Test fun `the decoded bitmap is the whole page at one scale either way`() {
        for (cropped in listOf(true, false)) {
            val (w, h) = target(cropped)
            assertEquals(pageW.toFloat() / pageH, w.toFloat() / h, 0.01f)
        }
    }

    @Test fun `a key that survives a flip is one whose decoded size did not move`() {
        // Full size decodes at source resolution whatever the crop, so both states share one
        // bitmap. Splitting them would decode and hold the same pixels twice.
        val full = { cropped: Boolean ->
            baseTargetSize(
                FitMode.FULL_SIZE, 4000, 4000, pageW, pageH,
                contentW = if (cropped) crop.width else pageW,
                contentH = if (cropped) crop.height else pageH,
                maxBaseWidth = null,
            )
        }
        assertEquals(key(full(true)), key(full(false)))
    }

    @Test fun `a tile key carries no crop because tiles are whole-page pixels`() {
        // Tiles are cut from the full page in source space and intersected with the crop per draw,
        // so a tile decoded under one crop state is correct under the other, and one entry serves
        // both. Pinning the fields means a crop field added to TileKey fails here, which is the
        // cue to revisit the draw path rather than let the two states silently split.
        val fields = TileKey::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("pageIndex", "col", "row", "sampleSize", "bookId"), fields)
    }

    @Test fun `a cap on width still yields a positive size`() {
        val (w, h) = target(cropped = true, max = 1)
        assertTrue(w >= 1 && h >= 1)
    }

    @Test fun `crop policy leaves a pdf uncropped whatever the setting`() {
        assertTrue(cropApplies(preference = true, isPdf = false))
        assertFalse(cropApplies(preference = false, isPdf = false))
        assertFalse(cropApplies(preference = true, isPdf = true))
        assertFalse(cropApplies(preference = false, isPdf = true))
    }

    @Test fun `a strip page falls back to its whole aspect when crop switches off`() {
        assertEquals(600f / 900f, stripAspectFor(crop, fullAspect = 1200f / 1800f)!!, 1e-6f)
        assertEquals(2f / 3f, stripAspectFor(null, fullAspect = 2f / 3f)!!, 1e-6f)
        assertNull(stripAspectFor(null, fullAspect = null))
    }

    @Test fun `strip aspects follow the crop on and off`() {
        val aspects = StripAspects()
        assertNull(aspects[0])
        aspects.loaded(0, 1000, 2000)
        assertEquals(0.5f, aspects[0]!!, 1e-6f)
        aspects.cropped(0, CropRect(0, 0, 500, 500))
        assertEquals(1f, aspects[0]!!, 1e-6f)
        aspects.cropped(0, null)
        assertEquals(0.5f, aspects[0]!!, 1e-6f)
    }
}
