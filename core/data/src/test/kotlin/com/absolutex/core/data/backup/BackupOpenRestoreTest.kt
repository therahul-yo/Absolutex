package com.absolutex.core.data.backup

import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupOpenRestoreTest : BackupFixture() {
    @Test fun `opening first cannot beat a backup position including an epoch-zero backup`() = runTest {
        for (time in listOf(0L, 100L)) {
            val id = "book-$time.cbz:100"
            db.progressDao().recordOpened(id, 100, 1000)
            val saved = ReadingProgress(id, 80, 100, time)
            val bytes = BackupWriter.write(BackupData("test", progress = listOf(saved)))
            val result = repository.restore(bytes.inputStream()) as RestoreResult.Complete
            assertEquals(1, result.progress)
            assertEquals(saved, db.progressDao().get(id))
            assertEquals(RestoreResult.Complete(0), repository.restore(bytes.inputStream()))
        }
    }

    @Test fun `an identical epoch-zero first-page backup stays idempotent`() = runTest {
        val id = "book.cbz:100"
        db.progressDao().recordOpened(id, 100, 1000)
        val saved = ReadingProgress(id, 0, 100, 0)
        val bytes = BackupWriter.write(BackupData("test", progress = listOf(saved)))
        assertEquals(RestoreResult.Complete(0), repository.restore(bytes.inputStream()))
    }

    @Test fun `reopening cannot make an older position beat a newer backup`() = runTest {
        val id = "book.cbz:100"
        db.progressDao().upsert(ReadingProgress(id, 20, 100, 100))
        db.progressDao().recordOpened(id, 100, 1000)
        val saved = ReadingProgress(id, 80, 100, 200)
        repository.restore(BackupWriter.write(BackupData("test", progress = listOf(saved))).inputStream())
        assertEquals(saved, db.progressDao().get(id))
    }
}
