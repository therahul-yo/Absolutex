package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress

internal const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
internal const val MAX_BOOKS = 10_000
internal const val MAX_ENTRIES = 100_000
internal const val MAX_TEXT = 1024
internal const val SCHEMA_VERSION = 1

internal data class BackupData(
    val appVersion: String,
    val progress: List<ReadingProgress> = emptyList(),
    val history: List<PageView> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val bookPrefs: List<BookPrefs> = emptyList(),
    val favourites: Set<String> = emptySet(),
    val preferences: Map<String, Any> = emptyMap(),
) {
    val bookIds: Set<String> get() = progress.map { it.bookId }.toSet() +
        history.map { it.bookKey } + bookmarks.map { it.bookId } + bookPrefs.map { it.bookId } + favourites
}

class FutureBackupVersion : IllegalArgumentException()

sealed interface RestoreResult {
    data class Complete(val books: Int) : RestoreResult
    data class ReadingDataOnly(val books: Int) : RestoreResult
}
