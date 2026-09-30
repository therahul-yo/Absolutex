package com.absolutex.feature.settings

import com.absolutex.core.data.backup.ExportResult
import com.absolutex.core.data.backup.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupMessagesTest {
    @Test fun `partial restore tells the user to retry settings separately from success`() {
        val complete = RestoreResult.Complete(5).toBackupState()
        val partial = RestoreResult.ReadingDataOnly(5).toBackupState()
        assertEquals(R.string.backup_restored, complete.message)
        assertEquals(R.string.backup_partial, partial.message)
        assertEquals(5, partial.books)
        assertFalse(partial.busy)
    }
    @Test fun `identical import says nothing new and settings only import reports settings`() {
        assertEquals(R.string.backup_nothing_new, RestoreResult.Complete(0).toBackupState().message)
        assertEquals(R.string.backup_settings_restored,
            RestoreResult.Complete(0, settingsChanged = true).toBackupState().message)
        val state = RestoreResult.Complete(2, progress = 1, favourites = 2, pendingDropped = 3).toBackupState()
        assertEquals(listOf(2, 1, 2), state.arguments)
        assertEquals(3, state.pendingDropped)
    }

    @Test fun `export result reports omitted history and skipped items`() {
        val state = ExportResult(byteArrayOf(), 3, 500, books = 10, bookmarks = 4, favourites = 2).toBackupState()
        assertEquals(R.string.backup_exported_with_omissions, state.message)
        assertEquals(listOf(10, 4, 2, 500L, 3L), state.arguments)
    }
    @Test fun `complete export reports the counts including an empty backup`() {
        val state = ExportResult(byteArrayOf(), 0, 0, books = 5, bookmarks = 3, favourites = 1).toBackupState()
        assertEquals(R.string.backup_exported, state.message)
        assertEquals(listOf(5, 3, 1, 0L, 0L), state.arguments)
        assertEquals(listOf(0, 0, 0, 0L, 0L), ExportResult(byteArrayOf(), 0, 0).toBackupState().arguments)
    }

}
