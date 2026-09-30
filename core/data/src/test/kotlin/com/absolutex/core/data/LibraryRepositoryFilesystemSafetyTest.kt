package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.LibraryScanner
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryFilesystemSafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AbsolutexDatabase
    private var clock = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo() = LibraryRepository(db.libraryDao(), LibraryScanner(parallelism = 2), { clock })

    @Test fun `an unreadable filesystem directory preserves old rows and keeps found books`() = runTest {
        val folder = tmp.newFolder("unreadable")
        val favourite = File(folder, "Favourite.cbz").apply { writeBytes(byteArrayOf(1)) }
        tmp.newFile("Gone.cbz")
        repo().scanLocation(tmp.root)
        repo().upsertFavorite(favourite.path, true)
        val before = db.libraryDao().allOnce().associateBy { it.path }
        clock += 1_000
        val found = tmp.newFile("Found.cbz")
        val unreadable = object : File(folder.path) {
            override fun listFiles(): Array<File>? = null
        }
        val root = object : File(tmp.root.path) {
            override fun listFiles(): Array<File> = arrayOf(unreadable, found)
        }
        val result = repo().scanLocation(root)
        assertEquals(ScanResult(found = 1, removed = 0, incomplete = true), result)
        val after = db.libraryDao().allOnce().associateBy { it.path }
        before.forEach { (path, row) -> assertEquals(row, after[path]) }
        assertTrue(after.getValue(favourite.path).isFavorite)
        assertEquals(clock, after.getValue(found.path).seenAtScan)
    }

    @Test fun `a readable empty filesystem directory still sweeps stale rows`() = runTest {
        val gone = tmp.newFile("Gone.cbz")
        repo().scanLocation(tmp.root)
        assertTrue(gone.delete())
        clock += 1_000
        val result = repo().scanLocation(tmp.root)
        assertFalse(result.incomplete)
        assertEquals(ScanResult(found = 0, removed = 1), result)
        assertTrue(db.libraryDao().allOnce().isEmpty())
    }
}
