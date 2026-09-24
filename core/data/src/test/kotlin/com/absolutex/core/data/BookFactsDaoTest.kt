package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookFactsDaoTest {

    private lateinit var db: AbsolutexDatabase
    private lateinit var facts: BookFactsDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        facts = db.bookFactsDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(path: String, count: Int? = null) {
        db.libraryDao().upsertAll(
            listOf(
                LibraryBook(
                    path = path,
                    contentKey = "$path:100",
                    series = null,
                    title = null,
                    issue = null,
                    issueRaw = null,
                    volume = null,
                    year = null,
                    sizeBytes = 100,
                    lastModified = 0,
                    isImageFolder = false,
                    pageCount = count,
                    addedAt = 0,
                    seenAtScan = 1,
                ),
            ),
        )
    }

    @Test fun `updatePageCount writes when the stored value is null`() = runTest {
        seed(path = "/a/book.cbz", count = null)
        assertEquals(1, facts.updatePageCount("/a/book.cbz", 42))
        assertEquals(42, db.libraryDao().allOnce().single { it.path == "/a/book.cbz" }.pageCount)
    }

    @Test fun `updatePageCount does not overwrite an existing count`() = runTest {
        seed(path = "/a/book.cbz", count = 42)
        assertEquals(0, facts.updatePageCount("/a/book.cbz", 99))
        assertEquals(42, db.libraryDao().allOnce().single { it.path == "/a/book.cbz" }.pageCount)
    }

    @Test fun `updatePageCount matches by path, not by absence`() = runTest {
        seed(path = "/a/other.cbz", count = null)
        assertEquals(0, facts.updatePageCount("/a/missing.cbz", 42))
        assertEquals(null, db.libraryDao().allOnce().single { it.path == "/a/other.cbz" }.pageCount)
    }
}
