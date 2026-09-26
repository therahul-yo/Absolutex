package com.absolutex.source.libarchive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the decision [LibArchiveSource.openPages] makes before it touches the archive.
 *
 * These are pure-JVM on purpose. The window path is proved equal to the single path by
 * tools/test-archive-window.sh against the real JNI, so what is left to test here is the
 * routing decision, and testing that through a file descriptor would be testing libarchive
 * again rather than this code.
 *
 * The four cases that matter are the ones where the routing can be quietly wrong: a run that
 * becomes a window, a set that must NOT, a request refused before any read, and a short result
 * refused rather than truncated.
 */
class WindowPlanTest {

    // A book whose sidecar is the FIRST raw entry, so pages start at ordinal 1. This is the
    // ordinary case and the reason page indexes and archive ordinals are not the same thing:
    // page 0 is ordinal 1, page 1 is ordinal 2, and ordinal 0 is ComicInfo.xml. Judging
    // consecutiveness on page indexes here would produce a run that does not exist.
    private val withSidecar = intArrayOf(1, 2, 3, 4, 5)
    // Pages whose ordinals have a gap, e.g. junk sitting between two pages.
    private val withJunk = intArrayOf(1, 2, 5, 6, 7)

    @Test fun consecutive_pages_become_one_run() {
        assertEquals(
            WindowPlan.Run(fromOrdinal = 1, count = 3),
            planWindow(listOf(0, 1, 2), withSidecar),
        )
    }

    @Test fun a_single_page_is_a_run_of_one() {
        // Worth saying explicitly: a one-element list is consecutive, and taking the window
        // path for it is correct and costs the same single walk openPage already paid.
        assertEquals(WindowPlan.Run(fromOrdinal = 2, count = 1), planWindow(listOf(1), withSidecar))
    }

    @Test fun a_run_may_start_partway_through_and_stop_short_of_the_end() {
        assertEquals(
            WindowPlan.Run(fromOrdinal = 3, count = 2),
            planWindow(listOf(2, 3), withSidecar),
        )
    }

    @Test fun scattered_pages_are_read_individually() {
        // The load-bearing negative. Pages 0 and 2 are not adjacent entries, so routing them
        // through one window call would ask libarchive for entries 1 and 2 and return page
        // 1's bytes as page 2 -- silently wrong rather than loudly wrong.
        assertEquals(WindowPlan.Individually, planWindow(listOf(0, 2), withSidecar))
    }

    @Test fun pages_straddling_a_junk_entry_are_not_a_run() {
        // Ordinals 2 and 5 are consecutive in PAGE order and not in ARCHIVE order. The plan
        // must follow the archive, which is the whole reason this function looks at ordinals.
        assertEquals(WindowPlan.Individually, planWindow(listOf(1, 2), withJunk))
    }

    @Test fun a_run_within_the_junked_ordinals_is_still_a_run() {
        assertEquals(
            WindowPlan.Run(fromOrdinal = 5, count = 3),
            planWindow(listOf(2, 3, 4), withJunk),
        )
    }

    @Test fun out_of_range_throws_before_anything_is_read() {
        // The whole request is refused, not truncated to the valid prefix: a caller that
        // received 3 of 4 pages has no way to tell which 3.
        val thrown = assertThrows(IndexOutOfBoundsException::class.java) {
            planWindow(listOf(0, 1, 99), withSidecar)
        }
        assertEquals("page 99 of 5", thrown.message)
        assertThrows(IndexOutOfBoundsException::class.java) { planWindow(listOf(-1), withSidecar) }
    }

    @Test fun empty_request_is_named_rather_than_silently_served() {
        assertThrows(IllegalArgumentException::class.java) { planWindow(emptyList(), withSidecar) }
    }

    @Test fun a_short_window_result_fails_rather_than_truncating() {
        // Short is the dangerous direction: the caller would index a shifted array and show
        // page N+1 where it meant page N.
        val thrown = assertThrows(IllegalStateException::class.java) {
            requireWindowSize(actual = 2, requested = 3)
        }
        assertEquals("window returned 2 entries for 3 pages", thrown.message)
    }

    @Test fun a_long_window_result_fails_too() {
        // Long means the ordinal contract drifted between nativeExtractWindow and
        // nativeExtract. Truncating would hide that; failing surfaces it.
        assertThrows(IllegalStateException::class.java) {
            requireWindowSize(actual = 4, requested = 3)
        }
    }

    @Test fun a_correctly_sized_window_is_accepted() {
        requireWindowSize(actual = 3, requested = 3)
        requireWindowSize(actual = 0, requested = 0)
    }
}
