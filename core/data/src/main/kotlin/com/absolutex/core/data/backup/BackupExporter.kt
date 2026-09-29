package com.absolutex.core.data.backup

import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.BookPrefs
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow

/** Volume and odd legacy rows reduce the export, never prevent backing up the remaining data. */
internal suspend fun AbsolutexDatabase.buildExport(
    version: String, pending: Map<String, Long>, preferences: Map<String, Any>,
): ExportResult {
    val dao = backupExportDao()
    val counts = dao.counts()
    var skipped = 0L
    fun include(id: String, valid: Boolean = true): Boolean {
        val keep = valid && validBackupIdentity(id)
        if (!keep) skipped++
        return keep
    }
    val progress = dao.progress(MAX_BOOKS).filter {
        include(it.bookId, it.pageCount > 0 && it.pageIndex in 0 until it.pageCount && it.updatedAt >= 0)
    }
    skipped += counts.progress - minOf(counts.progress, MAX_BOOKS.toLong())
    val prefs = dao.bookPrefs(MAX_BOOKS).filter { include(it.bookId) }.map(::knownBookPrefs)
    skipped += counts.prefs - minOf(counts.prefs, MAX_BOOKS.toLong())
    val ages = dao.favourites(MAX_BOOKS).associate { it.identity to it.time }.toMutableMap()
    pending.forEach { (identity, time) ->
        ages[identity] = maxOf(ages[identity] ?: time, time)
    }
    val favourites = ages.entries.sortedWith(
        compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
    )
        .map { it.key }.filter { include(it) }.toSet()
    skipped += counts.favourites - minOf(counts.favourites, MAX_BOOKS.toLong())
    val bookmarks = dao.bookmarks(MAX_ENTRIES).filter {
        include(it.bookId, it.pageIndex >= 0 && it.createdAt >= 0)
    }
    skipped += counts.bookmarks - minOf(counts.bookmarks, MAX_ENTRIES.toLong())
    var invalidHistory = 0
    val history = dao.history(EXPORT_HISTORY).filter {
        if (!validBackupIdentity(it.bookKey) || it.page < 0 || it.atEpochMs < 0) {
            skipped++
            invalidHistory++
            false
        } else true
    }
    val data = BackupData(version.take(MAX_TEXT), progress, history, bookmarks, prefs, favourites, preferences)
    val (bytes, kept) = data.writeSizedExport()
    val dropped = progress.size - kept.progress.size + bookmarks.size - kept.bookmarks.size +
        prefs.size - kept.bookPrefs.size + favourites.size - kept.favourites.size
    return ExportResult(bytes, skipped + dropped, counts.history - kept.history.size - invalidHistory)
}

private fun knownBookPrefs(value: BookPrefs): BookPrefs = value.copy(
    readingFlow = value.readingFlow?.takeIf { name -> ReadingFlow.entries.any { it.name == name } },
    pageLayout = value.pageLayout?.takeIf { name -> PageLayout.entries.any { it.name == name } },
)
