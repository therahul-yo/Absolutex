package com.absolutex.core.data.backup

import androidx.room.withTransaction
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress
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
class BackupExportTest : BackupFixture() {
    @Test fun `counts describe serialized unique books and retained entries`() = runTest {
        db.progressDao().upsert(ReadingProgress("a:1", 0, 10, 1))
        db.bookmarkDao().add(Bookmark("a:1", 0, 1))
        db.bookmarkDao().add(Bookmark("a:1", 2, 2))
        db.bookPrefsDao().upsert(BookPrefs("b:1"))
        val export = db.buildExport("test", mapOf("a:1" to 1L, "bad\n:1" to 1L), emptyMap())
        assertEquals(2, export.books)
        assertEquals(2, export.bookmarks)
        assertEquals(1, export.favourites)
        val data = BackupCodec.read(export.bytes.inputStream())
        assertEquals(data.bookIds.size, export.books)
        assertEquals(data.bookmarks.size, export.bookmarks)
        assertEquals(data.favourites.size, export.favourites)
    }

    @Test fun `history beyond the import cap exports the most recent bounded rows`() = runTest {
        val count = MAX_ENTRIES + 100
        db.pageViewDao().record((1..count).map { PageView(bookKey = "a:1", page = 0, atEpochMs = it.toLong()) })
        val export = repository.export("test")
        val data = BackupCodec.read(export.bytes.inputStream())
        assertEquals(EXPORT_HISTORY, data.history.size)
        assertEquals(count.toLong(), data.history.first().atEpochMs)
        assertEquals((count - EXPORT_HISTORY + 1).toLong(), data.history.last().atEpochMs)
        assertEquals((count - EXPORT_HISTORY).toLong(), export.omittedHistory)
        assertEquals(0L, export.skippedItems)
        assertEquals(count, db.pageViewDao().count())
        assertEquals(RestoreResult.Complete(0), repository.restore(export.bytes.inputStream()))
    }

    @Test fun `odd identities are skipped and counted in every export category`() = runTest {
        val ids = listOf("bad\nname:1", "x".repeat(MAX_TEXT) + ":1", "content://private/document:1")
        ids.forEach { id ->
            db.progressDao().upsert(ReadingProgress(id, 0, 1, 1))
            db.bookmarkDao().add(Bookmark(id, 0, 1))
            db.bookPrefsDao().upsert(BookPrefs(id))
            db.pageViewDao().record(listOf(PageView(bookKey = id, page = 0, atEpochMs = 1)))
        }
        db.progressDao().upsert(ReadingProgress("good:1", 0, 1, 1))
        val export = repository.export("test")
        val data = BackupCodec.read(export.bytes.inputStream())
        assertEquals(listOf("good:1"), data.progress.map { it.bookId })
        assertEquals(12L, export.skippedItems)
        assertEquals(0L, export.omittedHistory)
        assertFalse(export.bytes.toString(Charsets.UTF_8).contains("content://"))
        assertTrue(data.history.isEmpty() && data.bookmarks.isEmpty() && data.bookPrefs.isEmpty())
        assertEquals(RestoreResult.Complete(0), repository.restore(export.bytes.inputStream()))
    }

    @Test fun `eight thousand progress rows export completely and reimport strictly`() = runTest {
        db.withTransaction {
            repeat(8000) { index ->
                db.progressDao().upsert(ReadingProgress("book-$index-with-a-realistic-display-name.cbz:1024", 0, 1, 1))
            }
        }
        val export = repository.export("test")
        val data = BackupCodec.read(export.bytes.inputStream())
        assertEquals(8000, data.progress.size)
        assertEquals(8000, export.books)
        assertEquals(0L, export.skippedItems)
        assertEquals(0L, export.omittedHistory)
        assertEquals(RestoreResult.Complete(0), repository.restore(export.bytes.inputStream()))
    }

    @Test fun `actual over cap export drops oldest history before progress and reports exactly`() = runTest {
        val id = "x".repeat(1000) + ":1"
        db.withTransaction {
            repeat(2000) { index -> db.progressDao().upsert(ReadingProgress("$index$id", 0, 1, 1)) }
            db.bookmarkDao().add(Bookmark(id, 0, 1))
            db.pageViewDao().record((1..EXPORT_HISTORY).map {
                PageView(bookKey = id, page = 0, atEpochMs = it.toLong())
            })
        }
        val full = BackupData("test", db.backupDao().progress(), db.pageViewDao().all(), db.backupDao().bookmarks())
        assertThrows(BackupCapacityExceeded::class.java) { BackupWriter.write(full) }
        val export = repository.export("test")
        val data = BackupCodec.read(export.bytes.inputStream())
        assertEquals(2000, data.progress.size)
        assertEquals(data.bookIds.size, export.books)
        assertEquals(data.bookmarks.size, export.bookmarks)
        assertEquals(data.favourites.size, export.favourites)
        assertEquals(1, data.bookmarks.size)
        assertTrue(data.history.isNotEmpty() && data.history.size < EXPORT_HISTORY)
        assertEquals((EXPORT_HISTORY - data.history.size).toLong(), export.omittedHistory)
        assertEquals(0L, export.skippedItems)
        assertEquals(EXPORT_HISTORY.toLong(), data.history.first().atEpochMs)
        assertEquals((EXPORT_HISTORY - data.history.size + 1).toLong(), data.history.last().atEpochMs)
        assertTrue(export.bytes.size <= MAX_BACKUP_BYTES)
        assertEquals(RestoreResult.Complete(0), repository.restore(export.bytes.inputStream()))
        assertTrue(export.bytes.contentEquals(repository.export("test").bytes))
    }

    @Test fun `progress shrinks only after every other tier is empty`() = runTest {
        val rows = (1..5000).map { ReadingProgress("x".repeat(1000) + "$it:1", 1, 2, it.toLong()) }
        val id = rows.first().bookId
        val original = BackupData("test", rows.sortedByDescending { it.updatedAt },
            listOf(PageView(bookKey = id, page = 0, atEpochMs = 1)), listOf(Bookmark(id, 0, 1)),
            listOf(BookPrefs(id)), setOf(id))
        val (bytes, kept) = original.writeSizedExport()
        assertTrue(kept.progress.size < rows.size)
        assertTrue(kept.history.isEmpty() && kept.bookmarks.isEmpty())
        assertTrue(kept.favourites.isEmpty() && kept.bookPrefs.isEmpty())
        assertEquals(5000L, kept.progress.first().updatedAt)
        assertEquals(kept.progress, BackupCodec.read(bytes.inputStream()).progress)
        assertEquals(kept.progress.size, (repository.restore(bytes.inputStream()) as RestoreResult.Complete).progress)
    }
}
