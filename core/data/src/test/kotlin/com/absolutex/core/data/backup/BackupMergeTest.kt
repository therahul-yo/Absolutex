package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.pendingFavourites
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupMergeTest : BackupFixture() {
    @Test fun `newer progress and overrides win while bookmarks and history are unions`() = runTest {
        db.progressDao().upsert(ReadingProgress("a:1", 4, 10, 200))
        db.progressDao().upsert(ReadingProgress("kept:1", 1, 10, 50))
        db.bookPrefsDao().upsert(BookPrefs("a:1", "LTR"))
        db.bookmarkDao().add(Bookmark("a:1", 1, 200))
        db.pageViewDao().record(listOf(PageView(bookKey = "a:1", page = 4, atEpochMs = 200)))
        val older = BackupData("test", listOf(ReadingProgress("a:1", 2, 10, 100)),
            listOf(PageView(bookKey = "a:1", page = 2, atEpochMs = 100)),
            listOf(Bookmark("a:1", 1, 100), Bookmark("a:1", 2, 100)), listOf(BookPrefs("a:1", "RTL")))
        repeat(2) { repository.restore(BackupWriter.write(older).inputStream()) }
        assertEquals(4, db.progressDao().get("a:1")?.pageIndex)
        assertEquals("LTR", db.bookPrefsDao().get("a:1")?.readingFlow)
        assertNotNull(db.progressDao().get("kept:1"))
        assertEquals(2, db.pageViewDao().count())
        assertEquals(2, db.backupDao().bookmarks().size)
        assertEquals(200L, db.backupDao().bookmarks().first { it.pageIndex == 1 }.createdAt)
        val newer = older.copy(progress = listOf(ReadingProgress("a:1", 6, 10, 300)))
        repository.restore(BackupWriter.write(newer).inputStream())
        assertEquals(6, db.progressDao().get("a:1")?.pageIndex)
        assertEquals("RTL", db.bookPrefsDao().get("a:1")?.readingFlow)
    }

    @Test fun `duplicate imported bookmarks choose newest independently of input order`() = runTest {
        val data = BackupData("test", bookmarks = listOf(Bookmark("a:1", 2, 200), Bookmark("a:1", 2, 100)))
        repeat(2) { repository.restore(BackupWriter.write(data).inputStream()) }
        assertEquals(200L, db.backupDao().bookmarks().single().createdAt)
    }

    @Test fun `room failure rolls back every reading row`() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_view BEFORE INSERT ON page_view BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        val data = BackupData("test", listOf(ReadingProgress("a:1", 0, 1, 1)),
            listOf(PageView(bookKey = "a:1", page = 0, atEpochMs = 1)))
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.restore(BackupWriter.write(data).inputStream()) }
        }
        assertNull(db.progressDao().get("a:1"))
        assertEquals(0, db.pageViewDao().count())
    }

    @Test fun `datastore failure reports partial restore and retry is idempotent`() = runTest {
        val data = BackupData("test", listOf(ReadingProgress("a:1", 0, 1, 1)),
            listOf(PageView(bookKey = "a:1", page = 0, atEpochMs = 1)), favourites = setOf("a:1"),
            preferences = mapOf("true_black" to false))
        val bytes = BackupWriter.write(data)
        failSettings = true
        assertEquals(RestoreResult.ReadingDataOnly(1), repository.restore(bytes.inputStream()))
        assertNotNull(db.progressDao().get("a:1"))
        assertTrue(settings.currentAppPrefs().trueBlack)
        failSettings = false
        assertEquals(RestoreResult.Complete(1), repository.restore(bytes.inputStream()))
        assertEquals(1, db.pageViewDao().count())
        assertFalse(settings.currentAppPrefs().trueBlack)
        assertEquals(setOf("a:1"), settings.pendingFavourites())
    }
}
