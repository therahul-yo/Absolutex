package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.stats.PageSettled
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading history against real SQLite, on the JVM through Robolectric.
 *
 * The assertion that matters most is [a revisit is another view, not a correction]. Reading
 * history is append-only, and that is a decision rather than an accident: it is what keeps this
 * table clear of the trap `upsertPreservingAddedAt` exists for, where `@Insert(REPLACE)` deletes
 * and reinserts and so clears any column the caller did not resupply. A test that only round-trips
 * one batch would pass just as happily against a table keyed on (bookKey, page), which would
 * silently collapse every revisit and quietly undercount a reader's history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingHistoryTest {

    private lateinit var db: AbsolutexDatabase
    private lateinit var history: ReadingHistory

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        history = ReadingHistory(db.pageViewDao())
    }

    @After fun tearDown() = db.close()

    private val book = "Absolute Batman 001.cbr:12345"
    private val other = "Invincible 010.cbz:6789"

    private fun settle(page: Int, atEpochMs: Long, key: String = book) =
        PageSettled(bookKey = key, page = page, atEpochMs = atEpochMs)

    @Test
    fun `a batch round-trips in the domain's terms`() = runTest {
        val events = listOf(settle(1, 1_000), settle(2, 2_000))
        history.record(events)
        assertEquals(events, history.history())
    }

    @Test
    fun `an empty batch writes nothing and does not throw`() = runTest {
        history.record(emptyList())
        assertEquals(0, history.pagesRecorded())
    }

    @Test
    fun `a revisit is another view, not a correction`() = runTest {
        // Flipping back to a page is a fact about the reading, so both rows survive. A natural
        // key on (bookKey, page) — or any upsert — would keep one and lose the other.
        history.record(listOf(settle(4, 1_000)))
        history.record(listOf(settle(4, 9_000)))
        assertEquals(2, history.pagesRecorded())
        assertEquals(listOf(1_000L, 9_000L), history.history().map { it.atEpochMs })
    }

    @Test
    fun `a later batch leaves the earlier one untouched`() = runTest {
        history.record(listOf(settle(1, 1_000), settle(2, 2_000)))
        history.record(listOf(settle(1, 3_000, other)))
        assertEquals(3, history.pagesRecorded())
        assertEquals(
            listOf(book, book, other),
            history.history().map { it.bookKey },
        )
    }

    @Test
    fun `history comes back in time order however it was written`() = runTest {
        history.record(listOf(settle(3, 3_000)))
        history.record(listOf(settle(1, 1_000)))
        assertEquals(listOf(1_000L, 3_000L), history.history().map { it.atEpochMs })
    }

    @Test
    fun `a window excludes what fell outside it`() = runTest {
        history.record(listOf(settle(1, 1_000), settle(2, 5_000), settle(3, 9_000)))
        assertEquals(listOf(5_000L, 9_000L), history.historySince(5_000).map { it.atEpochMs })
    }

    @Test
    fun `forgetting removes all of it`() = runTest {
        history.record(listOf(settle(1, 1_000), settle(2, 2_000, other)))
        history.forget()
        assertEquals(0, history.pagesRecorded())
        assertEquals(emptyList<PageSettled>(), history.history())
    }
}
