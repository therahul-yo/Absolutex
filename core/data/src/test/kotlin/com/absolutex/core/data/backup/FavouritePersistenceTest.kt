package com.absolutex.core.data.backup

import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.core.data.settings.pendingFavourites
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavouritePersistenceTest : BackupFixture() {
    private var scanId = 0L
    private fun library() = LibraryRepository(db.libraryDao(), LibraryScanner(), { ++scanId })

    @Test fun `forgetting and readding a folder restores its favourites`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        library.upsertFavorite(book.path, true)
        assertEquals(1, library.forgetLocation(tmp.root.path))
        assertTrue(db.libraryDao().allOnce().isEmpty())
        library.scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
    }

    @Test fun `watcher removal and a return at a different path preserve identity favourites`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        db.bookFactsDao().setFavourite("a.cbz:16", true)
        book.delete()
        library.applyChange(LibraryChange.Removed(book.path), tmp.root)
        val moved = tmp.newFolder("moved").resolve("a.cbz").apply { writeBytes(ByteArray(16)) }
        library.applyChange(LibraryChange.Added(moved.path), tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
    }

    @Test fun `a completed scan can remove and later rediscover a favourite`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        library.upsertFavorite(book.path, true)
        book.delete()
        library.scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().isEmpty())
        book.writeBytes(ByteArray(16))
        library.scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
    }

    @Test fun `toggles apply to every copy and unfavouriting survives folder removal`() = runTest {
        tmp.newFile("a.cbz").writeBytes(ByteArray(16))
        tmp.newFolder("copy").resolve("a.cbz").writeBytes(ByteArray(16))
        val library = library()
        library.scanLocation(tmp.root)
        val path = db.libraryDao().allOnce().first().path
        library.upsertFavorite(path, true)
        assertTrue(db.libraryDao().allOnce().all { it.isFavorite })
        db.bookFactsDao().setFavourite("a.cbz:16", false)
        assertFalse(db.bookFactsDao().observeFavourite("a.cbz:16").first()!!)
        library.forgetLocation(tmp.root.path)
        library.scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().none { it.isFavorite })
        assertTrue(db.backupDao().favourites().isEmpty())
    }

    @Test fun `a different book at a new path does not inherit a missing favourite`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        library.upsertFavorite(book.path, true)
        library.forgetLocation(tmp.root.path)
        book.delete()
        tmp.newFolder("other").resolve("a.cbz").writeBytes(ByteArray(32))
        library.scanLocation(tmp.root)
        assertFalse(db.libraryDao().allOnce().single().isFavorite)
    }

    @Test fun `backup retains forgotten favourites and reimport stays idempotent`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        library.upsertFavorite(book.path, true)
        library.forgetLocation(tmp.root.path)
        val export = repository.export("test")
        assertEquals(1, export.favourites)
        assertEquals(setOf("a.cbz:16"), BackupCodec.read(export.bytes.inputStream()).favourites)
        repeat(2) {
            assertEquals(RestoreResult.Complete(0), repository.restore(export.bytes.inputStream()))
        }
        assertTrue(settings.pendingFavourites().isEmpty())
        library.scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
    }

    @Test fun `a failed library toggle rolls back the durable favourite too`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        val library = library()
        library.scanLocation(tmp.root)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_toggle BEFORE UPDATE OF isFavorite ON library_book " +
                "BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        assertThrows(Exception::class.java) { runBlocking { library.upsertFavorite(book.path, true) } }
        assertTrue(db.backupDao().favourites().isEmpty())
        assertFalse(db.libraryDao().allOnce().single().isFavorite)
    }
}
