package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressOpenedTest {
    private lateinit var db: AbsolutexDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `opening records actual page zero with separate recency`() = runTest {
        val position = db.progressDao().recordOpened("a.cbz:100", 20, 100)
        assertEquals(ReadingProgress("a.cbz:100", 0, 20, 0), position)
        assertEquals(position, db.progressDao().mostRecent())
        assertEquals(100L, db.pageViewDao().all().single().atEpochMs)
    }

    @Test fun `reopening preserves the position timestamp and refreshes known count`() = runTest {
        val saved = ReadingProgress("a.cbz:100", 12, 20, 1)
        db.progressDao().upsert(saved)
        assertEquals(saved.copy(pageCount = 21), db.progressDao().recordOpened(saved.bookId, 21, 100))
    }

    @Test fun `an opening cannot overwrite a saved position beyond the new count`() = runTest {
        val saved = ReadingProgress("a.cbz:100", 19, 20, 1)
        db.progressDao().upsert(saved)
        assertEquals(saved, db.progressDao().recordOpened(saved.bookId, 10, 100))
    }

    @Test fun `clearing progress leaves other books and reading history alone`() = runTest {
        db.progressDao().recordOpened("a.cbz:100", 20, 100)
        db.progressDao().recordOpened("b.cbz:100", 20, 50)
        db.pageViewDao().record(listOf(PageView(bookKey = "a.cbz:100", page = 0, atEpochMs = 100)))
        db.progressDao().clear("a.cbz:100")
        assertNull(db.progressDao().get("a.cbz:100"))
        assertEquals("b.cbz:100", db.progressDao().mostRecent()?.bookId)
        assertEquals(3, db.pageViewDao().count())
    }
}
