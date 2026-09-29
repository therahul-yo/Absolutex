package com.absolutex.feature.settings

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
}
