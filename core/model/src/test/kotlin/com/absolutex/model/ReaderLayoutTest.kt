package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitModeMemoryTest {

    private val portraitScreenSpread =
        FitContext(ScreenOrientation.PORTRAIT, PageOrientation.LANDSCAPE)
    private val portraitScreenPage =
        FitContext(ScreenOrientation.PORTRAIT, PageOrientation.PORTRAIT)

    @Test fun `a spread on a portrait screen defaults to fit width`() {
        // The case the default exists for: a double-page spread on a phone held upright is
        // unreadable fitted to screen.
        assertEquals(FitMode.FIT_WIDTH, FitModeMemory().modeFor(portraitScreenSpread))
    }

    @Test fun `everything else defaults to fit screen`() {
        assertEquals(FitMode.FIT_SCREEN, FitModeMemory().modeFor(portraitScreenPage))
        assertEquals(
            FitMode.FIT_SCREEN,
            FitModeMemory().modeFor(FitContext(ScreenOrientation.LANDSCAPE, PageOrientation.LANDSCAPE)),
        )
    }

    @Test fun `a choice is remembered for its own context only`() {
        val memory = FitModeMemory().with(portraitScreenPage, FitMode.FULL_SIZE)
        assertEquals(FitMode.FULL_SIZE, memory.modeFor(portraitScreenPage))
        // Rotating, or turning to a spread, must not inherit it — that is the whole point of
        // remembering per context rather than globally.
        assertEquals(FitMode.FIT_WIDTH, memory.modeFor(portraitScreenSpread))
    }

    @Test fun `all four contexts are independent`() {
        var memory = FitModeMemory()
        val modes = FitMode.entries
        val contexts = ScreenOrientation.entries.flatMap { s ->
            PageOrientation.entries.map { p -> FitContext(s, p) }
        }
        contexts.forEachIndexed { i, c -> memory = memory.with(c, modes[i % modes.size]) }
        contexts.forEachIndexed { i, c -> assertEquals(modes[i % modes.size], memory.modeFor(c)) }
    }

    @Test fun `memory survives a round trip through persistence`() {
        val memory = FitModeMemory()
            .with(portraitScreenPage, FitMode.FIT_HEIGHT)
            .with(portraitScreenSpread, FitMode.FULL_SIZE)
        val restored = FitModeMemory.fromPairs(memory.asPairs())
        assertEquals(FitMode.FIT_HEIGHT, restored.modeFor(portraitScreenPage))
        assertEquals(FitMode.FULL_SIZE, restored.modeFor(portraitScreenSpread))
    }

    @Test fun `garbage in stored preferences falls back to defaults instead of crashing`() {
        val restored = FitModeMemory.fromPairs(
            mapOf("nonsense" to "FIT_SCREEN", "PORTRAIT:PORTRAIT" to "NOT_A_MODE", "" to ""),
        )
        assertEquals(FitMode.FIT_SCREEN, restored.modeFor(portraitScreenPage))
    }

    @Test fun `page orientation comes from the page's own shape`() {
        assertEquals(PageOrientation.PORTRAIT, PageOrientation.of(1988, 3057))
        assertEquals(PageOrientation.LANDSCAPE, PageOrientation.of(3976, 3057))
        // A square page fits a portrait screen without splitting, so it is not a spread.
        assertEquals(PageOrientation.PORTRAIT, PageOrientation.of(1000, 1000))
    }

    @Test fun `context is derived from screen and page together`() {
        val context = FitContext.of(screenWidth = 1240, screenHeight = 2772, pageWidth = 3976, pageHeight = 3057)
        assertEquals(FitContext(ScreenOrientation.PORTRAIT, PageOrientation.LANDSCAPE), context)
    }
}

class PageOrderTest {

    @Test fun `left to right keeps book order`() {
        assertEquals(0, PageOrder.pagerIndexOf(0, 45, ReadingFlow.LTR))
        assertEquals(44, PageOrder.pagerIndexOf(44, 45, ReadingFlow.LTR))
    }

    @Test fun `right to left reverses so page one sits on the right`() {
        assertEquals(44, PageOrder.pagerIndexOf(0, 45, ReadingFlow.RTL))
        assertEquals(0, PageOrder.pagerIndexOf(44, 45, ReadingFlow.RTL))
    }

    @Test fun `vertical reads in book order like LTR`() {
        assertEquals(7, PageOrder.pagerIndexOf(7, 45, ReadingFlow.VERTICAL))
    }

    @Test fun `mapping round-trips for every page in every flow`() {
        // The property that matters: progress, bookmarks and prefetch all speak book order, so a
        // page that survives a trip through the pager and back must come out unchanged.
        for (flow in ReadingFlow.entries) {
            for (count in listOf(1, 2, 45, 180)) {
                for (page in 0 until count) {
                    val pagerIndex = PageOrder.pagerIndexOf(page, count, flow)
                    assertTrue("$flow index $pagerIndex out of range", pagerIndex in 0 until count)
                    assertEquals("$flow page $page of $count", page, PageOrder.pageAt(pagerIndex, count, flow))
                }
            }
        }
    }

    @Test fun `a single page book is not reversed into a negative index`() {
        assertEquals(0, PageOrder.pagerIndexOf(0, 1, ReadingFlow.RTL))
    }

    @Test fun `an empty book does not produce a negative index`() {
        assertEquals(0, PageOrder.pagerIndexOf(0, 0, ReadingFlow.RTL))
    }
}

class TapGridTest {

    private val w = 1240
    private val h = 2772

    private fun zone(xFraction: Float, yFraction: Float, mirrored: Boolean = false) =
        TapGrid.zoneAt(w * xFraction, h * yFraction, w, h, mirrored)

    @Test fun `the nine zones map to the nine thirds`() {
        assertEquals(TapZone.TOP_LEFT, zone(0.1f, 0.1f))
        assertEquals(TapZone.TOP_CENTER, zone(0.5f, 0.1f))
        assertEquals(TapZone.TOP_RIGHT, zone(0.9f, 0.1f))
        assertEquals(TapZone.MIDDLE_LEFT, zone(0.1f, 0.5f))
        assertEquals(TapZone.CENTER, zone(0.5f, 0.5f))
        assertEquals(TapZone.MIDDLE_RIGHT, zone(0.9f, 0.5f))
        assertEquals(TapZone.BOTTOM_LEFT, zone(0.1f, 0.9f))
        assertEquals(TapZone.BOTTOM_CENTER, zone(0.5f, 0.9f))
        assertEquals(TapZone.BOTTOM_RIGHT, zone(0.9f, 0.9f))
    }

    @Test fun `mirroring swaps the columns but never the rows`() {
        assertEquals(TapZone.TOP_RIGHT, zone(0.1f, 0.1f, mirrored = true))
        assertEquals(TapZone.BOTTOM_LEFT, zone(0.9f, 0.9f, mirrored = true))
        // The centre column and every row are unaffected.
        assertEquals(TapZone.TOP_CENTER, zone(0.5f, 0.1f, mirrored = true))
        assertEquals(TapZone.CENTER, zone(0.5f, 0.5f, mirrored = true))
    }

    @Test fun `coordinates outside the view are clamped, not crashes`() {
        // A fling or a cutout can report a coordinate just outside the surface; an unclamped
        // index walks off the end of the enum.
        assertEquals(TapZone.TOP_LEFT, TapGrid.zoneAt(-50f, -50f, w, h))
        assertEquals(TapZone.BOTTOM_RIGHT, TapGrid.zoneAt(w + 50f, h + 50f, w, h))
    }

    @Test fun `a zero-sized view answers centre rather than dividing by zero`() {
        assertEquals(TapZone.CENTER, TapGrid.zoneAt(10f, 10f, 0, 0))
    }

    @Test fun `every point on screen lands in exactly one zone`() {
        val counts = HashMap<TapZone, Int>()
        for (xi in 0..40) {
            for (yi in 0..40) {
                counts.merge(zone(xi / 40f, yi / 40f), 1, Int::plus)
            }
        }
        assertEquals("all nine zones should be reachable", 9, counts.size)
        assertNotEquals(0, counts[TapZone.CENTER])
    }
}

class TapZoneColumnTest {

    @Test fun `column is the position within the row, so page turns need no grid maths`() {
        assertEquals(0, TapZone.TOP_LEFT.column)
        assertEquals(1, TapZone.TOP_CENTER.column)
        assertEquals(2, TapZone.TOP_RIGHT.column)
        assertEquals(0, TapZone.BOTTOM_LEFT.column)
        assertEquals(2, TapZone.MIDDLE_RIGHT.column)
    }

    @Test fun `a mirrored tap on the left reports the right column, so RTL turns forward there`() {
        val zone = TapGrid.zoneAt(x = 10f, y = 500f, width = 1200, height = 2700, mirrored = true)
        assertEquals(2, zone.column)
    }
}

class LayoutForScreenTest {
    @Test fun `two-page layouts fall back to one page on an upright screen`() {
        assertEquals(PageLayout.SINGLE, PageLayout.DOUBLE.forScreen(landscape = false))
        assertEquals(PageLayout.SINGLE, PageLayout.DOUBLE_WITH_COVER.forScreen(landscape = false))
        assertEquals(PageLayout.DOUBLE, PageLayout.DOUBLE.forScreen(landscape = true))
        assertEquals(PageLayout.CONTINUOUS_VERTICAL, PageLayout.CONTINUOUS_VERTICAL.forScreen(landscape = false))
    }
}
