package com.absolutex.core.decode

import com.absolutex.model.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrefetchPlanner maths, pure JVM (no Robolectric by design — §3 keeps the test path honest).
 * Window shape, both book ends, spread layouts, and eviction ordering.
 */
class PrefetchPlannerTest {

    @Test
    fun `single layout windows forward page by page`() {
        val w = PrefetchPlanner.window(5, 200, PageLayout.SINGLE, +1, 3)
        assertEquals(listOf(6, 7, 8), w)
    }

    @Test
    fun `single layout windows backward on reversal`() {
        val w = PrefetchPlanner.window(5, 200, PageLayout.SINGLE, -1, 3)
        assertEquals(listOf(4, 3, 2), w)
    }

    @Test
    fun `window at the start of the book plans nothing behind page zero`() {
        val w = PrefetchPlanner.window(0, 200, PageLayout.SINGLE, -1, 3)
        assertTrue(w.isEmpty())
    }

    @Test
    fun `window at the end of the book is clipped, never wrapping`() {
        val w = PrefetchPlanner.window(198, 200, PageLayout.SINGLE, +1, 5)
        assertEquals(listOf(199), w)
    }

    @Test
    fun `window on the last page plans nothing`() {
        val w = PrefetchPlanner.window(199, 200, PageLayout.SINGLE, +1, 4)
        assertTrue(w.isEmpty())
    }

    @Test
    fun `short books clip the window to the book`() {
        val w = PrefetchPlanner.window(1, 3, PageLayout.SINGLE, +1, 5)
        assertEquals(listOf(2), w)
    }

    @Test
    fun `spread layout advances spread by spread`() {
        // DOUBLE: spreads are (0,1),(2,3),(4,5)... From page 4 (spread (4,5)) forward:
        // the NEXT spreads (6,7) and (8,9). Page 5 — the other half of the CURRENT spread —
        // is already composed by the pager and loads through normal composition; prefetching
        // it would decode a page the reader already owns.
        val w = PrefetchPlanner.window(4, 200, PageLayout.DOUBLE, +1, 4)
        assertEquals(listOf(6, 7, 8, 9), w)
    }

    @Test
    fun `double with cover keeps the cover spread`() {
        // DOUBLE_WITH_COVER: spreads are (0),(1,2),(3,4)... From page 1 (spread (1,2))
        // forward: (3,4). Page 2 is the current spread's other half — see the test above.
        val w = PrefetchPlanner.window(1, 200, PageLayout.DOUBLE_WITH_COVER, +1, 2)
        assertEquals(listOf(3, 4), w)
    }

    @Test
    fun `continuous strip advances page by page like single`() {
        val w = PrefetchPlanner.window(10, 200, PageLayout.CONTINUOUS_VERTICAL, +1, 2)
        assertEquals(listOf(11, 12), w)
    }

    @Test
    fun `zero depth or empty book plans nothing`() {
        assertTrue(PrefetchPlanner.window(0, 0, PageLayout.SINGLE, +1, 3).isEmpty())
        assertTrue(PrefetchPlanner.window(5, 200, PageLayout.SINGLE, +1, 0).isEmpty())
    }

    @Test
    fun `out-of-range page plans nothing`() {
        assertTrue(PrefetchPlanner.window(-1, 200, PageLayout.SINGLE, +1, 3).isEmpty())
        assertTrue(PrefetchPlanner.window(200, 200, PageLayout.SINGLE, +1, 3).isEmpty())
    }

    @Test
    fun `evict order is farthest first`() {
        val order = PrefetchPlanner.evictOrder(setOf(1, 3, 5, 9, 11), page = 6, direction = +1)
        assertEquals(listOf(1, 11, 3, 9, 5), order)
    }

    @Test
    fun `evict ties break toward the page behind the direction of travel`() {
        // Forward reading: page 4 (behind) evicts before page 8 (ahead), both 2 away.
        val order = PrefetchPlanner.evictOrder(setOf(4, 8), page = 6, direction = +1)
        assertEquals(listOf(4, 8), order)
        // Backward reading flips the tie-break.
        val backward = PrefetchPlanner.evictOrder(setOf(4, 8), page = 6, direction = -1)
        assertEquals(listOf(8, 4), backward)
    }

    @Test
    fun `behind set is the opposite side of travel`() {
        val behind = PrefetchPlanner.behind(setOf(1, 2, 9, 10), page = 5, direction = +1)
        assertEquals(setOf(1, 2), behind)
        val behindBackward = PrefetchPlanner.behind(setOf(1, 2, 9, 10), page = 5, direction = -1)
        assertEquals(setOf(9, 10), behindBackward)
    }

    @Test
    fun `behind set with no direction is empty`() {
        assertTrue(PrefetchPlanner.behind(setOf(1, 2, 9), page = 5, direction = 0).isEmpty())
    }
}
