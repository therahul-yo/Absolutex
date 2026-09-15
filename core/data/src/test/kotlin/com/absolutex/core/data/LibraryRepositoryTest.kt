package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.LibraryScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Scanner and database together: what the library screens will actually sit on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AbsolutexDatabase
    private var clock = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo() = LibraryRepository(
        dao = db.libraryDao(),
        scanner = LibraryScanner(parallelism = 2),
        now = { clock },
    )

    private fun file(path: String, bytes: Int = 16): File {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test fun `a scan persists what it finds`() = runTest {
        file("Batman/Batman 001.cbz")
        file("Batman/Batman 002.cbz")
        val result = repo().scanLocation(tmp.root)
        assertEquals(2, result.found)
        val stored = db.libraryDao().observeAll().first()
        assertEquals(2, stored.size)
        assertEquals("Batman", stored.first().series)
    }

    @Test fun `rescanning after a delete removes the book`() = runTest {
        val gone = file("Comics/Gone 001.cbz")
        file("Comics/Kept 001.cbz")
        repo().scanLocation(tmp.root)

        assertTrue(gone.delete())
        clock += 1_000
        val second = repo().scanLocation(tmp.root)

        assertEquals(1, second.found)
        assertEquals(1, second.removed)
        assertEquals(listOf("Kept"), db.libraryDao().observeAll().first().map { it.series })
    }

    @Test fun `rescanning one location never empties another`() = runTest {
        // The bug this guards: an unscoped stale sweep deletes every book on the SD card the
        // moment internal storage is rescanned.
        val sd = File(tmp.root, "sd").apply { mkdirs() }
        val internal = File(tmp.root, "internal").apply { mkdirs() }
        file("sd/Batman 001.cbz")
        file("internal/Superman 001.cbz")

        repo().scanLocation(sd)
        clock += 1_000
        repo().scanLocation(internal)
        clock += 1_000
        val again = repo().scanLocation(internal)

        assertEquals(0, again.removed)
        val series = db.libraryDao().observeAll().first().mapNotNull { it.series }.sorted()
        assertEquals(listOf("Batman", "Superman"), series)
    }

    @Test fun `a rescan keeps the date the book first appeared`() = runTest {
        file("Comics/Batman 001.cbz")
        repo().scanLocation(tmp.root)
        val firstSeen = db.libraryDao().observeAll().first().single().addedAt

        clock += 50_000
        repo().scanLocation(tmp.root)
        val row = db.libraryDao().observeAll().first().single()

        assertEquals("addedAt must survive a rescan or 'recently added' shows everything", firstSeen, row.addedAt)
        assertEquals("seenAtScan must advance", clock, row.seenAtScan)
    }

    @Test fun `a rescan picks up a file that changed size`() = runTest {
        file("Comics/Batman 001.cbz", bytes = 10)
        repo().scanLocation(tmp.root)
        file("Comics/Batman 001.cbz", bytes = 5_000)
        clock += 1_000
        repo().scanLocation(tmp.root)
        assertEquals(5_000L, db.libraryDao().observeAll().first().single().sizeBytes)
    }

    @Test fun `search returns the whole library for a blank query`() = runTest {
        file("Comics/Batman 001.cbz")
        file("Comics/Superman 001.cbz")
        val repo = repo()
        repo.scanLocation(tmp.root)
        assertEquals(2, repo.search("   ").size)
        assertEquals(1, repo.search("batman").size)
    }

    @Test fun `an image folder is stored as one book with its page count`() = runTest {
        file("Loose Pages/001.jpg")
        file("Loose Pages/002.jpg")
        file("Loose Pages/003.jpg")
        repo().scanLocation(tmp.root)
        val book = db.libraryDao().observeAll().first().single()
        assertTrue(book.isImageFolder)
        assertEquals(3, book.pageCount)
    }
}
