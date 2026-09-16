package com.absolutex.model

/** How many pages one screen shows (§5.2 page layouts). */
enum class PageLayout {
    SINGLE,

    /** Two pages side by side: 1-2, 3-4, … */
    DOUBLE,

    /**
     * Two pages side by side after a cover that stands alone: 1, 2-3, 4-5, … Printed comics are
     * bound this way, so the facing pages of a spread only line up when the cover is on its own.
     */
    DOUBLE_WITH_COVER,

    /** Pages stacked top to bottom and scrolled freely, fit to width: webtoons and long strips. */
    CONTINUOUS_VERTICAL,
}

/**
 * Groups a book's pages into what one screen shows, in book order.
 *
 * Everything outside the pager — progress, resume, bookmarks, seeking — stays in book pages; only
 * the pager counts spreads. [indexOf] is the one way back from a book page to the spread that shows
 * it, so a layout change can reopen on the page being read rather than on spread N of a new layout.
 *
 * Not yet: a page that is itself a double-page spread (wider than tall) should stand alone. Its
 * dimensions are unknown until it is decoded, and regrouping then would move the pager under the
 * reader, so that waits for dimensions from the archive index.
 */
object Spreads {

    fun of(pageCount: Int, layout: PageLayout): List<IntRange> {
        if (pageCount <= 0) return emptyList()
        if (layout == PageLayout.SINGLE || layout == PageLayout.CONTINUOUS_VERTICAL) {
            return (0 until pageCount).map { it..it }
        }
        val spreads = ArrayList<IntRange>((pageCount + 1) / 2 + 1)
        var first = 0
        if (layout == PageLayout.DOUBLE_WITH_COVER) {
            spreads += 0..0
            first = 1
        }
        while (first < pageCount) {
            val last = minOf(first + 1, pageCount - 1)
            spreads += first..last
            first = last + 1
        }
        return spreads
    }

    /** The spread showing book [page], clamped to the book: spreads are ordered and contiguous. */
    fun indexOf(spreads: List<IntRange>, page: Int): Int {
        if (spreads.isEmpty()) return 0
        var low = 0
        var high = spreads.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (spreads[mid].first <= page) low = mid else high = mid - 1
        }
        return low
    }
}
