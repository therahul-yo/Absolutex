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
class LibraryLearnedCountTest {
    private lateinit var db: AbsolutexDatabase
    private val book = LibraryBook(
        path = "/comics/a.cbz", contentKey = "a.cbz:100", series = null, title = null,
        issue = null, issueRaw = null, volume = null, year = null, sizeBytes = 100,
        lastModified = 1, isImageFolder = false, pageCount = null, addedAt = 1, seenAtScan = 1,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `a cover count survives rescanning the same file`() = runTest {
        db.libraryDao().upsertAll(listOf(book))
        db.bookFactsDao().updatePageCount(book.path, 42)
        db.libraryDao().upsertPreservingAddedAt(listOf(book.copy(seenAtScan = 2)))
        assertEquals(42, db.libraryDao().allOnce().single().pageCount)
    }

    @Test fun `a replaced file or changed modification time discards the learned count`() = runTest {
        for (changed in listOf(book.copy(contentKey = "a.cbz:200", sizeBytes = 200), book.copy(lastModified = 2))) {
            db.libraryDao().upsertAll(listOf(book.copy(pageCount = 42)))
            db.libraryDao().upsertPreservingAddedAt(listOf(changed))
            assertNull(db.libraryDao().allOnce().single().pageCount)
        }
    }

    @Test fun `a fresh scanner count takes precedence over a learned count`() = runTest {
        db.libraryDao().upsertAll(listOf(book.copy(pageCount = 42)))
        db.libraryDao().upsertPreservingAddedAt(listOf(book.copy(pageCount = 43)))
        assertEquals(43, db.libraryDao().allOnce().single().pageCount)
    }
}
