package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupExportTest : BackupFixture() {
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
    }

    @Test fun `long conforming identities exhaust byte budget without failing export`() = runTest {
        repeat(MAX_BOOKS + 1) { index ->
            val id = "x".repeat(1000) + "$index:1"
            db.progressDao().upsert(ReadingProgress(id, 0, 1, 1))
            db.bookmarkDao().add(Bookmark(id, 0, 1))
            db.pageViewDao().record(listOf(PageView(bookKey = id, page = 0, atEpochMs = 1)))
        }
        val export = repository.export("test")
        assertTrue(export.skippedItems > 0)
        assertTrue(export.bytes.size <= MAX_BACKUP_BYTES)
        assertTrue(BackupCodec.read(export.bytes.inputStream()).bookIds.size <= MAX_BOOKS)
    }
}
