package com.absolutex.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsTest {

    @Test fun `single shows one page per screen`() {
        assertEquals(listOf(0..0, 1..1, 2..2), Spreads.of(3, PageLayout.SINGLE))
    }

    @Test fun `double pairs pages and leaves an odd last page alone`() {
        assertEquals(listOf(0..1, 2..3, 4..4), Spreads.of(5, PageLayout.DOUBLE))
        assertEquals(listOf(0..1, 2..3), Spreads.of(4, PageLayout.DOUBLE))
    }

    @Test fun `double with cover puts the cover alone so facing pages line up`() {
        assertEquals(listOf(0..0, 1..2, 3..4), Spreads.of(5, PageLayout.DOUBLE_WITH_COVER))
        assertEquals(listOf(0..0, 1..2, 3..3), Spreads.of(4, PageLayout.DOUBLE_WITH_COVER))
    }

    @Test fun `every page appears exactly once, in order, for every layout and length`() {
        for (layout in PageLayout.entries) {
            for (count in 0..47) {
                val pages = Spreads.of(count, layout).flatMap { it.toList() }
                assertEquals("$layout x $count", (0 until count).toList(), pages)
            }
        }
    }

    @Test fun `index of a page is the spread that shows it`() {
        val spreads = Spreads.of(45, PageLayout.DOUBLE_WITH_COVER)
        for ((index, spread) in spreads.withIndex()) {
            spread.forEach { page -> assertEquals("page $page", index, Spreads.indexOf(spreads, page)) }
        }
    }

    @Test fun `index is clamped to the book`() {
        val spreads = Spreads.of(10, PageLayout.DOUBLE)
        assertEquals(0, Spreads.indexOf(spreads, -3))
        assertEquals(spreads.lastIndex, Spreads.indexOf(spreads, 99))
        assertEquals(0, Spreads.indexOf(emptyList(), 5))
    }

    @Test fun `a one-page book is one spread in every layout`() {
        PageLayout.entries.forEach { assertEquals(listOf(0..0), Spreads.of(1, it)) }
    }

    @Test fun `a pager index past a just-shrunk list reads the last spread, not out of bounds`() {
        // The launch crash: a resumed pager at 93 over a book whose count settled at 90.
        val spreads = Spreads.of(90, PageLayout.SINGLE)
        assertEquals(89..89, Spreads.at(spreads, 93))
    }

    @Test fun `an index inside the list reads exactly that spread`() {
        val spreads = Spreads.of(10, PageLayout.DOUBLE)
        assertEquals(spreads[2], Spreads.at(spreads, 2))
    }

    @Test fun `a negative index reads the first spread`() {
        assertEquals(0..0, Spreads.at(Spreads.of(5, PageLayout.SINGLE), -1))
    }

    @Test fun `no spreads at all reads empty rather than throwing`() {
        assertEquals(IntRange.EMPTY, Spreads.at(emptyList(), 3))
    }
}
