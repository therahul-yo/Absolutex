package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room runs on the JVM here through Robolectric, so the DAO is covered without a device. The
 * SQLite underneath is real, which is the point: these assertions are about SQL, not about Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDaoTest {

    private lateinit var db: AbsolutexDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.libraryDao()
    }

    @After fun tearDown() = db.close()

    private fun book(
        path: String,
        series: String? = "Batman",
        issue: Double? = 1.0,
        size: Long = 100,
        scan: Long = 1,
    ) = LibraryBook(
        path = path,
        contentKey = "${path.substringAfterLast('/')}:$size",
        series = series,
        title = null,
        issue = issue,
        issueRaw = issue?.toInt()?.toString(),
        volume = null,
        year = null,
        sizeBytes = size,
        lastModified = 0,
        isImageFolder = false,
        pageCount = null,
        addedAt = 0,
        seenAtScan = scan,
    )

    @Test fun `upsert then observe returns the books`() = runTest {
        dao.upsertAll(listOf(book("/a/Batman 001.cbz"), book("/a/Batman 002.cbz", issue = 2.0)))
        assertEquals(2, dao.observeAll().first().size)
    }

    @Test fun `rescanning the same path replaces rather than duplicates`() = runTest {
        dao.upsertAll(listOf(book("/a/Batman 001.cbz", size = 100)))
        dao.upsertAll(listOf(book("/a/Batman 001.cbz", size = 999)))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(999L, all.single().sizeBytes)
    }

    @Test fun `books order by series then issue, unshelved last`() = runTest {
        dao.upsertAll(
            listOf(
                book("/a/Batman 010.cbz", issue = 10.0),
                book("/a/Batman 002.cbz", issue = 2.0),
                book("/a/mystery.cbz", series = null, issue = null),
                book("/a/Alpha 001.cbz", series = "Alpha"),
            ),
        )
        val order = dao.observeAll().first().map { it.series to it.issue }
        assertEquals(
            listOf("Alpha" to 1.0, "Batman" to 2.0, "Batman" to 10.0, null to null),
            order,
        )
    }

    @Test fun `search matches series, title and path`() = runTest {
        dao.upsertAll(listOf(book("/a/Batman 001.cbz"), book("/a/Superman 001.cbz", series = "Superman")))
        assertEquals(1, dao.search("Batman", "Batman").size)
        assertEquals(1, dao.search("Superman", "Superman").size)
        assertEquals(2, dao.search("001", "001").size)
        assertEquals(0, dao.search("Wonder", "Wonder").size)
    }

    @Test fun `search also matches the encoded form against path, for a SAF document Uri`() = runTest {
        // A SAF book's path is a percent-encoded document Uri; the raw, unencoded query never
        // appears in it literally, only the encoded form does.
        val safPath = "content://com.android.externalstorage.documents/tree/primary%3AComics/" +
            "document/primary%3AComics%2FAbsolute%20Batman%20001%20(2024).cbr"
        dao.upsertAll(listOf(book(safPath, series = null)))
        assertEquals(0, dao.search("Absolute Batman 001", "Absolute Batman 001").size)
        assertEquals(1, dao.search("Absolute Batman 001", "Absolute%20Batman%20001").size)
    }

    @Test fun `a stale scan deletes only books under the scanned location`() = runTest {
        dao.upsertAll(
            listOf(
                book("/sd/Comics/Gone.cbz", scan = 1),
                book("/sd/Comics/Kept.cbz", scan = 2),
                book("/internal/Other.cbz", scan = 1),
            ),
        )
        val removed = dao.deleteStaleIn("/sd/Comics", scanId = 2)
        assertEquals(1, removed)
        val left = dao.observeAll().first().map { it.path }.sorted()
        // The other location's book must survive: scanning one root cannot empty another.
        assertEquals(listOf("/internal/Other.cbz", "/sd/Comics/Kept.cbz"), left)
    }

    @Test fun `books in a series come back in issue order`() = runTest {
        dao.upsertAll(
            listOf(
                book("/a/Batman 003.cbz", issue = 3.0),
                book("/a/Batman 001.cbz", issue = 1.0),
            ),
        )
        assertEquals(listOf(1.0, 3.0), dao.booksInSeries("Batman").map { it.issue })
    }

    @Test fun `a rescan keeps addedAt and the favourite flag the user set`() = runTest {
        dao.upsertAll(listOf(book("/a/Batman 001.cbz").copy(addedAt = 111, isFavorite = true)))
        // A scan re-derives every column from disk, so it carries addedAt 0 and isFavorite false.
        dao.upsertPreservingAddedAt(listOf(book("/a/Batman 001.cbz", size = 999, scan = 2)))
        val row = dao.observeAll().first().single()
        assertEquals(111L, row.addedAt)
        assertTrue("a rescan must not clear a favourite", row.isFavorite)
        assertEquals(999L, row.sizeBytes)
    }

    @Test fun `reading progress and library live in one database`() = runTest {
        // The library is regenerable by rescanning; progress is not. Both must coexist.
        db.progressDao().upsert(ReadingProgress("book-1", 12, 45, 1_700_000_000_000))
        dao.upsertAll(listOf(book("/a/Batman 001.cbz")))
        assertEquals(12, db.progressDao().get("book-1")!!.pageIndex)
        assertEquals(1, dao.observeAll().first().size)
        assertNull(db.progressDao().get("missing"))
    }
}
