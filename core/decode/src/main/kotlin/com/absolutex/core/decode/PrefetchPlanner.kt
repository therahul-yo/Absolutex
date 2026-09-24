package com.absolutex.core.decode

import com.absolutex.model.PageLayout
import com.absolutex.model.Spreads
import kotlin.math.abs

/**
 * Pure planning maths for the prefetch engine (§3): which pages to decode ahead of the reader,
 * in what order, and which resident pages to drop first. No coroutines, no Android, no clock —
 * every function is total and deterministic so the window, eviction and reversal behaviour is
 * unit-tested on the JVM alone (see PrefetchPlannerTest).
 *
 * All indices are BOOK indices. Direction is expressed once, as a signed travel direction
 * derived from consecutive settles (+1 forward, −1 backward), because book order is what
 * progress, bookmarks and the archive speak: RTL is a pager-axis concern (PageOrder reverses
 * it at the pager boundary), not a book-order concern, so the planner needs no RTL case.
 */
object PrefetchPlanner {

    /**
     * The pages worth decoding ahead of [page], nearest first, moving [direction] pages at a
     * time through the book. A spread layout advances spread by spread so both pages of the
     * next spread are prefetched together; SINGLE and CONTINUOUS_VERTICAL advance page by page.
     *
     * The window is capped at [depth] book pages in each direction, and at the book's ends:
     * nothing past page 0 or [pageCount) is ever planned, so a 5-deep window at the last page
     * of a 10-page book plans nothing rather than wrapping to its start.
     */
    fun window(
        page: Int,
        pageCount: Int,
        layout: PageLayout,
        direction: Int,
        depth: Int,
    ): List<Int> {
        if (pageCount <= 0 || depth <= 0) return emptyList()
        if (page !in 0 until pageCount) return emptyList()
        val step = stepForLayout(layout)
        val dir = if (direction >= 0) 1 else -1
        val planned = ArrayList<Int>(depth)
        var cursor = page + dir * step
        var pagesLeft = depth
        while (cursor in 0 until pageCount && pagesLeft > 0) {
            if (step == 2 && layout.isSpreadLayout()) {
                cursor = addSpreadPlanned(cursor, page, pageCount, layout, planned) { pagesLeft -= it }
            } else {
                if (cursor != page && cursor !in planned) planned += cursor
                pagesLeft--
            }
            cursor += dir * step
        }
        return planned
    }

    /** Step size: 2 for spread layouts (both halves together), 1 for single/continuous. */
    private fun stepForLayout(layout: PageLayout): Int =
        if (layout == PageLayout.SINGLE || layout == PageLayout.CONTINUOUS_VERTICAL) 1 else 2

    private fun PageLayout.isSpreadLayout(): Boolean =
        this != PageLayout.SINGLE && this != PageLayout.CONTINUOUS_VERTICAL

    /** Adds the spread containing [cursor] to [planned], returns the cursor unchanged. */
    private fun addSpreadPlanned(
        cursor: Int,
        page: Int,
        pageCount: Int,
        layout: PageLayout,
        planned: ArrayList<Int>,
        decrement: (Int) -> Unit,
    ): Int {
        val spreads = Spreads.of(pageCount, layout)
        val spreadIndex = Spreads.indexOf(spreads, cursor)
        val spread = spreads.getOrNull(spreadIndex) ?: cursor..cursor
        for (p in spread) {
            if (p != page && p !in planned) planned += p
        }
        decrement(spread.count())
        return cursor
    }

    /**
     * Resident pages ordered for eviction: farthest from [page] first. Ties break toward
     * the page behind the reading direction ([direction]) — when the budget forces a drop,
     * the page the reader is moving away from goes before the one it is moving toward.
     * (it - page) * direction is negative exactly for behind pages, so they sort first.
     */
    fun evictOrder(resident: Set<Int>, page: Int, direction: Int): List<Int> {
        return resident.sortedWith(
            compareByDescending<Int> { abs(it - page) }
                .thenBy { (it - page) * direction },
        )
    }

    /**
     * Which in-flight prefetches are now behind the reader: pages on the opposite side of
     * [page] from [direction]. A reversal makes everything decoded for the old direction
     * behind-work in one step; this is the set the engine cancels promptly.
     */
    fun behind(inFlight: Set<Int>, page: Int, direction: Int): Set<Int> {
        if (direction == 0) return emptySet()
        return inFlight.filterTo(HashSet()) { (it - page).sign() == -direction.sign() }.let {
            it
        }
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
