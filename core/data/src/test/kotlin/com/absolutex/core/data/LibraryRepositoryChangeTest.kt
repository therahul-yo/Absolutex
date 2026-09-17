package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.scan.LibraryChange
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

/**
 * The change stream applied to the persisted library: `Added`/`Modified` upsert, `Removed`
 * deletes, `RescanRequested` re-walks (§5.1, no manual rescan).
 *
 * This is the Repository half of the wiring, exercised directly because the locating half — which
 * location a change's path belongs to — is the locations store's job when it lands, and it must
 * not be able to make the database touch a root it was not asked to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryChangeTest {

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

    private suspend fun stored(): List<LibraryBook> = db.libraryDao().observeAll().first()

    @Test fun `an added book is upserted`() = runTest {
        val added = file("Comics/Batman 001.cbz")
        val result = repo().applyChange(LibraryChange.Added(added.path), tmp.root)

        assertEquals(ChangeResult.Upserted(added.path), result)
        assertEquals(listOf(added.path), stored().map { it.path })
        assertEquals("Batman", stored().single().series)
    }

    @Test fun `an added path that is no book writes nothing`() = runTest {
        val junk = file("Comics/notes.txt")
        val result = repo().applyChange(LibraryChange.Added(junk.path), tmp.root)

        assertEquals(ChangeResult.NotABook(junk.path), result)
        assertTrue(stored().isEmpty())
    }

    @Test fun `an added path that vanished before the write writes nothing`() = runTest {
        // A burst can report a file that is gone by the time the write lands — a temp file, or
        // the tail of an extractor's rename dance. That is not an error and not a row.
        val missing = File(tmp.root, "Comics/Ghost 001.cbz")
        val result = repo().applyChange(LibraryChange.Added(missing.path), tmp.root)

        assertEquals(ChangeResult.NotABook(missing.path), result)
        assertTrue(stored().isEmpty())
    }

    @Test fun `a promoted folder becomes its book`() = runTest {
        val dir = File(tmp.root, "Loose Pages")
        file("Loose Pages/001.jpg")
        file("Loose Pages/002.jpg")
        val result = repo().applyChange(LibraryChange.FolderPromoted(dir.path, imageCount = 2), tmp.root)

        assertEquals(ChangeResult.Upserted(dir.path), result)
        val row = stored().single()
        assertTrue(row.isImageFolder)
        assertEquals(2, row.pageCount)
    }

    @Test fun `a removed path deletes its row`() = runTest {
        val gone = file("Comics/Gone 001.cbz")
        file("Comics/Kept 001.cbz")
        repo().scanLocation(tmp.root)
        assertTrue(gone.delete())

        val result = repo().applyChange(LibraryChange.Removed(gone.path), tmp.root)

        assertEquals(ChangeResult.Removed(gone.path), result)
        assertEquals(listOf("Kept"), stored().map { it.series })
    }

    @Test fun `a rescan drops rows the walk no longer sees and keeps the rest`() = runTest {
        val gone = file("Comics/Gone 001.cbz")
        file("Comics/Kept 001.cbz")
        repo().scanLocation(tmp.root)
        assertTrue(gone.delete())
        clock += 1_000

        val result = repo().applyChange(LibraryChange.RescanRequested, tmp.root)

        assertEquals(ChangeResult.Rescanned(tmp.root), result)
        assertEquals(listOf("Kept"), stored().map { it.series })
    }

    @Test fun `a rescan never reaches another location's rows`() = runTest {
        val sd = File(tmp.root, "sd").apply { mkdirs() }
        val internal = File(tmp.root, "internal").apply { mkdirs() }
        file("sd/Batman 001.cbz")
        file("internal/Superman 001.cbz")
        repo().scanLocation(tmp.root)

        val result = repo().applyChange(LibraryChange.RescanRequested, internal)

        assertEquals(ChangeResult.Rescanned(internal), result)
        val series = stored().mapNotNull { it.series }.sorted()
        assertEquals(listOf("Batman", "Superman"), series)
    }

    @Test fun `a change for an unknown location is dropped`() = runTest {
        file("Comics/Batman 001.cbz")
        val result = repo().applyChange(LibraryChange.Added(File(tmp.root, "x.cbz").path), null)

        assertEquals(ChangeResult.Ignored, result)
        assertTrue(stored().isEmpty())
    }

    @Test fun `re-adding a book keeps the date it first appeared`() = runTest {
        val book = file("Comics/Batman 001.cbz")
        repo().scanLocation(tmp.root)
        val firstSeen = stored().single().addedAt

        clock += 50_000
        assertEquals(ChangeResult.Upserted(book.path), repo().applyChange(LibraryChange.Modified(book.path), tmp.root))

        assertEquals(firstSeen, stored().single().addedAt)
    }
}
