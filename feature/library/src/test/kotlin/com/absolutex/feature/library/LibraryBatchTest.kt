package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Mark read and Mark unread write.
 *
 * This is the fix for the first blocker in the lead's review: `setRead` trusted
 * `LibraryBook.pageCount`, which the scanner leaves null for every container, so Mark read on an
 * opened 45-page CBR always answered "needs opening first" — and Mark unread then wrote
 * `pageCount = 0` over the 45 the reader had stored.
 */
class LibraryBatchTest {

    private fun container(learned: Int?) = ReadTarget(
        bookId = "Batman 001.cbr:900",
        scannedPageCount = null,
        storedPageCount = learned,
    )

    @Test fun `mark read uses the count the reader learned when the scan has none`() {
        val plan = planSetRead(listOf(container(learned = 45)), read = true)
        assertEquals(
            listOf(ReadWrite("Batman 001.cbr:900", pageIndex = 44, pageCount = 45)),
            plan.writes,
        )
        assertEquals(0, plan.skipped)
    }

    @Test fun `mark read skips a book nobody has opened`() {
        // Nothing anywhere knows where the end is, so "read" cannot be written honestly.
        val plan = planSetRead(listOf(container(learned = null)), read = true)
        assertTrue(plan.writes.isEmpty())
        assertEquals(1, plan.skipped)
    }

    @Test fun `mark unread keeps the count the reader learned`() {
        // The second half of the same bug: page 0 clears the position, the count survives.
        val plan = planSetRead(listOf(container(learned = 45)), read = false)
        assertEquals(
            listOf(ReadWrite("Batman 001.cbr:900", pageIndex = 0, pageCount = 45)),
            plan.writes,
        )
    }

    @Test fun `mark unread on a book with no known count writes zero rather than nothing`() {
        // There is a position row to clear (the selection got here somehow); clearing it needs a
        // row, and 0 is the honest count when nobody knows one.
        val plan = planSetRead(listOf(container(learned = null)), read = false)
        assertEquals(listOf(ReadWrite("Batman 001.cbr:900", pageIndex = 0, pageCount = 0)), plan.writes)
        assertEquals(0, plan.skipped)
    }

    @Test fun `the scan wins for an image folder`() {
        val folder = ReadTarget(bookId = "Loose Pages:2048", scannedPageCount = 12, storedPageCount = null)
        val plan = planSetRead(listOf(folder), read = true)
        assertEquals(ReadWrite("Loose Pages:2048", pageIndex = 11, pageCount = 12), plan.writes.single())
    }

    @Test fun `a zero from either side counts as unknown`() {
        // A truncated archive reports 0 images; treating that as a page count would write
        // "finished" at index -1.
        val zeroScan = ReadTarget("a:1", scannedPageCount = 0, storedPageCount = 30)
        assertEquals(
            ReadWrite("a:1", pageIndex = 29, pageCount = 30),
            planSetRead(listOf(zeroScan), read = true).writes.single(),
        )

        val zeroStored = ReadTarget("b:1", scannedPageCount = null, storedPageCount = 0)
        assertEquals(1, planSetRead(listOf(zeroStored), read = true).skipped)
    }

    @Test fun `a mixed selection reports what it could not do`() {
        val plan = planSetRead(
            listOf(container(learned = 3), container(learned = null), container(learned = 9)),
            read = true,
        )
        assertEquals(2, plan.writes.size)
        assertEquals(1, plan.skipped)
    }

    @Test fun `an empty selection plans nothing`() {
        val plan = planSetRead(emptyList(), read = true)
        assertTrue(plan.writes.isEmpty())
        assertEquals(0, plan.skipped)
    }
}
