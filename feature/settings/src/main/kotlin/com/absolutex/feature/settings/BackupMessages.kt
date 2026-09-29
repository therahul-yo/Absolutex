package com.absolutex.feature.settings

import com.absolutex.core.data.backup.ExportResult
import com.absolutex.core.data.backup.RestoreResult

/** Keep partial success distinct: retry is necessary even though reading data was committed. */
internal fun RestoreResult.toBackupState(): BackupState = when (this) {
    is RestoreResult.Complete -> BackupState(
        message = when {
            books > 0 -> R.string.backup_restored
            settingsChanged -> R.string.backup_settings_restored
            else -> R.string.backup_nothing_new
        },
        books = books, arguments = listOf(books, progress, favourites), pendingDropped = pendingDropped,
    )
    is RestoreResult.ReadingDataOnly -> BackupState(
        message = R.string.backup_partial, books = books, arguments = listOf(books, progress, favourites),
    )
}

internal fun ExportResult.toBackupState(): BackupState = BackupState(
    message = if (skippedItems == 0L && omittedHistory == 0L) R.string.backup_exported
        else R.string.backup_exported_with_omissions,
    arguments = listOf(omittedHistory, skippedItems),
)
