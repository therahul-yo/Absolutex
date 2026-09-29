package com.absolutex.core.data.backup

import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.PageView
import com.absolutex.core.data.ReadingProgress

internal const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
internal const val MAX_BOOKS = 10_000
internal const val MAX_PENDING_FAVOURITES = 2000
internal const val MAX_ENTRIES = 10_000
internal const val MAX_TEXT = 1024
internal const val SCHEMA_VERSION = 1
internal const val MAX_JSON_VALUES = 100_000
internal const val EXPORT_HISTORY = 5000
internal const val ONE_DAY_MS = 86_400_000L

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

open class InvalidBackup : IllegalArgumentException()

class FutureBackupVersion : IllegalArgumentException()

data class ExportResult(val bytes: ByteArray, val skippedItems: Long, val omittedHistory: Long)

sealed interface RestoreResult {
    data class Complete(
        val books: Int,
        val progress: Int = 0,
        val favourites: Int = 0,
        val settingsChanged: Boolean = false,
        val pendingDropped: Int = 0,
    ) : RestoreResult
    data class ReadingDataOnly(val books: Int, val progress: Int = 0, val favourites: Int = 0) : RestoreResult
}

internal data class ReadingChanges(
    val books: Set<String>, val progress: Int, val favourites: Int, val unmatched: Set<String>,
)

internal class BackupCapacityExceeded : InvalidBackup()

internal fun BackupData.withinExportLimits(): Boolean =
    bookIds.size <= MAX_BOOKS && progress.size <= MAX_BOOKS && bookPrefs.size <= MAX_BOOKS &&
        history.size <= MAX_ENTRIES && bookmarks.size <= MAX_ENTRIES && favourites.size <= MAX_BOOKS &&
        jsonValueCount() <= MAX_JSON_VALUES

/** Exact value count for this fixed schema, including preference-set arrays. */
private fun BackupData.jsonValueCount(): Int = ROOT_VALUES + progress.size * PROGRESS_VALUES +
    (history.size + bookmarks.size + bookPrefs.size) * ROW_VALUES + favourites.size +
    preferences.values.sumOf { if (it is Set<*>) 1 + it.size else 1 }

private const val ROOT_VALUES = 9
private const val PROGRESS_VALUES = 5
private const val ROW_VALUES = 4
