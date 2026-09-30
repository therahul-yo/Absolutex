package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingHistory
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.stats.activeDays
import com.absolutex.core.stats.pagesPerDay
import com.absolutex.core.stats.readingTimePerBook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpeningHistoryTest : BackupFixture() {
    @Test fun `opening and reopening change recency without adding stats or exported history`() = runTest {
        val id = "book.cbz:100"
        val views = listOf(1000L, 2000L).map { PageView(bookKey = id, page = 80, atEpochMs = it) }
        db.pageViewDao().record(views)
        db.progressDao().upsert(ReadingProgress(id, 80, 100, 2000))
        val history = ReadingHistory(db.pageViewDao())
        val before = history.history()
        val zone = ZoneId.of("UTC")
        for (time in listOf(3000L, 4000L, 86_400_000L)) db.progressDao().recordOpened(id, 100, time)
        val after = history.historySince(0)
        assertEquals(before, after)
        assertEquals(pagesPerDay(before, zone), pagesPerDay(after, zone))
        assertEquals(activeDays(before, zone), activeDays(after, zone))
        assertEquals(readingTimePerBook(before), readingTimePerBook(after))
        assertEquals(2, history.pagesRecorded())
        assertEquals(86_400_000L, db.pageViewDao().observeLastRead().first().single().atEpochMs)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM page_view WHERE page = -1").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        assertEquals(2000L, db.backupDao().lastRead(id))
        assertEquals(2L, db.backupExportDao().counts().history)
        val exported = repository.export("test")
        assertEquals(0L, exported.skippedItems)
        assertEquals(0L, exported.omittedHistory)
        assertEquals(views.reversed(), BackupCodec.read(exported.bytes.inputStream()).history.map { it.copy(id = 0) })
    }

    @Test fun `opening markers cannot make local book preferences beat newer backup preferences`() = runTest {
        val id = "book.cbz:100"
        db.progressDao().upsert(ReadingProgress(id, 20, 100, 100))
        db.bookPrefsDao().upsert(BookPrefs(id, "LTR"))
        db.progressDao().recordOpened(id, 100, 1000)
        val data = BackupData("test", progress = listOf(ReadingProgress(id, 80, 100, 200)),
            bookPrefs = listOf(BookPrefs(id, "RTL")))
        repository.restore(BackupWriter.write(data).inputStream())
        assertEquals("RTL", db.bookPrefsDao().get(id)?.readingFlow)
    }

    @Test fun `opening then closing a new book leaves history and stats empty`() = runTest {
        db.progressDao().recordOpened("new.cbz:100", 100, 86_400_000L)
        val history = ReadingHistory(db.pageViewDao())
        val events = history.history()
        assertEquals(emptyList<com.absolutex.core.stats.PageSettled>(), events)
        assertEquals(emptySet<java.time.LocalDate>(), activeDays(events, ZoneId.of("UTC")))
        assertEquals(0, history.pagesRecorded())
        val exported = repository.export("test")
        assertEquals(0L, exported.skippedItems)
        assertEquals(0L, exported.omittedHistory)
        assertEquals(emptyList<PageView>(), BackupCodec.read(exported.bytes.inputStream()).history)
    }

}
