package com.absolutex.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

    // The reference page from the real corpus: Absolute Batman 001, 1988x3057.
    private val w = 1988
    private val h = 3057

    @Test fun `grid covers the whole image with clipped edge tiles`() {
        val all = TileGrid.visibleTiles(w, h, 0, 0, w, h, scale = 1f, overscan = 0)
        assertEquals(TileGrid.columns(w) * TileGrid.rows(h), all.size)
        // No tile may extend past the image, or BitmapRegionDecoder throws.
        assertTrue(all.all { it.right <= w && it.bottom <= h })
        assertTrue(all.all { it.width > 0 && it.height > 0 })
        // Coverage must be exact: summed tile area equals image area.
        assertEquals(w.toLong() * h, all.sumOf { it.width.toLong() * it.height })
    }

    @Test fun `zoomed viewport pulls far fewer tiles than the full page`() {
        val full = TileGrid.visibleTiles(w, h, 0, 0, w, h, 1f, overscan = 0).size
        val zoomed = TileGrid.visibleTiles(w, h, 900, 1400, 1200, 1700, 4f, overscan = 0).size
        assertTrue("zoomed=$zoomed should be far below full=$full", zoomed < full / 3)
    }

    @Test fun `overscan adds a ring but never escapes the image`() {
        val none = TileGrid.visibleTiles(w, h, 900, 1400, 1200, 1700, 4f, overscan = 0)
        val one = TileGrid.visibleTiles(w, h, 900, 1400, 1200, 1700, 4f, overscan = 1)
        assertTrue(one.size > none.size)
        assertTrue(one.all { it.left >= 0 && it.top >= 0 && it.right <= w && it.bottom <= h })
    }

    @Test fun `viewport clamped at the image edge does not produce negative tiles`() {
        val t = TileGrid.visibleTiles(w, h, 0, 0, 100, 100, 4f, overscan = 2)
        assertTrue(t.all { it.col >= 0 && it.row >= 0 })
        assertTrue(t.all { it.left >= 0 && it.top >= 0 })
    }

    @Test fun `viewport past the far edge stays inside the grid`() {
        val t = TileGrid.visibleTiles(w, h, w - 10, h - 10, w + 500, h + 500, 4f, overscan = 2)
        assertTrue(t.isNotEmpty())
        assertTrue(t.all { it.right <= w && it.bottom <= h })
    }

    @Test fun `empty or inverted viewport asks for nothing, not everything`() {
        assertTrue(TileGrid.visibleTiles(w, h, 500, 500, 500, 500, 1f).isEmpty())
        assertTrue(TileGrid.visibleTiles(w, h, 800, 800, 200, 200, 1f).isEmpty())
    }

    @Test fun `degenerate image size yields no tiles rather than crashing`() {
        assertTrue(TileGrid.visibleTiles(0, 0, 0, 0, 10, 10, 1f).isEmpty())
        assertTrue(TileGrid.visibleTiles(-5, 100, 0, 0, 10, 10, 1f).isEmpty())
    }

    @Test fun `sample size is always a power of two`() {
        val scales = listOf(0.01f, 0.1f, 0.24f, 0.25f, 0.4f, 0.5f, 1f, 2f, 4f)
        for (s in scales) {
            val n = TileGrid.sampleSizeFor(s)
            assertTrue("sampleSize $n for scale $s is not a power of two", n > 0 && (n and (n - 1)) == 0)
        }
    }

    @Test fun `sample size never subsamples when zoomed in`() {
        assertEquals(1, TileGrid.sampleSizeFor(1f))
        assertEquals(1, TileGrid.sampleSizeFor(4f))
        assertEquals(1, TileGrid.sampleSizeFor(0.6f))
    }

    @Test fun `sample size grows as the page shrinks`() {
        assertTrue(TileGrid.sampleSizeFor(0.1f) > TileGrid.sampleSizeFor(0.4f))
        assertTrue(TileGrid.sampleSizeFor(0.01f) > TileGrid.sampleSizeFor(0.1f))
    }

    @Test fun `non-positive scale degrades to full resolution, never zero or negative`() {
        assertEquals(1, TileGrid.sampleSizeFor(0f))
        assertEquals(1, TileGrid.sampleSizeFor(-1f))
    }

    @Test fun `a 12000px page stays within the memory budget at 400 percent`() {
        // §3's stress case. At 400% on a 1240x2772 viewport, resident tiles must stay modest.
        val big = 12000
        val tiles = TileGrid.visibleTiles(big, big, 5000, 6000, 5000 + 1240, 6000 + 2772, 4f, overscan = 1)
        val bytes = tiles.sumOf { it.width.toLong() * it.height * 4 }
        assertTrue("resident tile bytes ${bytes / 1048576} MB too high", bytes < 64L * 1024 * 1024)
        assertTrue("expected a real tile set, got ${tiles.size}", tiles.size in 10..60)
    }
}
