package com.absolutex.core.data.backup

import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.settings.pendingFavourites
import com.absolutex.core.data.settings.restoreBackup
import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.LibraryScanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingFavouritesTest : BackupFixture() {
    private fun repo() = LibraryRepository(db.libraryDao(), LibraryScanner(), { 100L }, pending)

    @Test fun `scan applies matching pending entries and clears only those entries`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        settings.restoreBackup(emptyMap(), setOf("a.cbz:16", "missing.cbz:16"))
        repo().scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        assertEquals(setOf("missing.cbz:16"), settings.pendingFavourites())
        repo().scanLocation(tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        assertEquals(book.path, db.libraryDao().allOnce().single().path)
    }

    @Test fun `delta upsert applies pending favourites and imports union existing favourites`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        settings.restoreBackup(emptyMap(), setOf("a.cbz:16"))
        repo().applyChange(LibraryChange.Added(book.path), tmp.root)
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        assertTrue(settings.pendingFavourites().isEmpty())
        repository.restore(BackupWriter.write(BackupData("test")).inputStream())
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        repo().upsertFavorite(book.path, false)
        repository.restore(BackupWriter.write(BackupData("test", favourites = setOf("a.cbz:16"))).inputStream())
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        assertTrue(settings.pendingFavourites().isEmpty())
    }

    @Test fun `failed pending clear preserves retry and does not lose the favourite`() = runTest {
        val book = tmp.newFile("a.cbz").apply { writeBytes(ByteArray(16)) }
        settings.restoreBackup(emptyMap(), setOf("a.cbz:16"))
        failSettings = true
        assertThrows(java.io.IOException::class.java) {
            kotlinx.coroutines.runBlocking { repo().applyChange(LibraryChange.Added(book.path), tmp.root) }
        }
        assertTrue(db.libraryDao().allOnce().single().isFavorite)
        assertEquals(setOf("a.cbz:16"), settings.pendingFavourites())
        failSettings = false
        repo().scanLocation(tmp.root)
        assertTrue(settings.pendingFavourites().isEmpty())
    }
}
