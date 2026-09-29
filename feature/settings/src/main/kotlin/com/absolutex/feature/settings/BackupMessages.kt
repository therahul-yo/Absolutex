package com.absolutex.feature.settings

import com.absolutex.core.data.backup.RestoreResult

/** Keep partial success distinct: retry is necessary even though reading data was committed. */
internal fun RestoreResult.toBackupState(): BackupState = when (this) {
    is RestoreResult.Complete -> BackupState(message = R.string.backup_restored, books = books)
    is RestoreResult.ReadingDataOnly -> BackupState(message = R.string.backup_partial, books = books)
}
