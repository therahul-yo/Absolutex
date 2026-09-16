package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class BookmarkDaoTest {

    private lateinit var db: AbsolutexDatabase
    private lateinit var dao: BookmarkDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookmarkDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `pages come back in page order, for that book only`() = runTest {
        dao.add(Bookmark("a.cbz:1", 12, 0))
        dao.add(Bookmark("a.cbz:1", 3, 0))
        dao.add(Bookmark("b.cbz:1", 7, 0))
        assertEquals(listOf(3, 12), dao.pages("a.cbz:1").first())
    }

    @Test fun `bookmarking a page twice keeps one row`() = runTest {
        dao.add(Bookmark("a.cbz:1", 3, 0))
        dao.add(Bookmark("a.cbz:1", 3, 1))
        assertEquals(listOf(3), dao.pages("a.cbz:1").first())
    }

    @Test fun `remove says whether there was a bookmark`() = runTest {
        dao.add(Bookmark("a.cbz:1", 3, 0))
        assertEquals(1, dao.remove("a.cbz:1", 3))
        assertEquals(0, dao.remove("a.cbz:1", 3))
        assertEquals(emptyList<Int>(), dao.pages("a.cbz:1").first())
    }
}
