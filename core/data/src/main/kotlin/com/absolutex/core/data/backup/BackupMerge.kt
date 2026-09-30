package com.absolutex.core.data.backup

import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.ReadingProgress

/** Invoked only inside the caller's Room transaction. Existing data is never deleted. */
internal suspend fun AbsolutexDatabase.mergeBackup(data: BackupData): ReadingChanges {
    val dao = backupDao()
    val changed = mutableSetOf<String>()
    val oldTimes = data.bookPrefs.associate { row ->
        row.bookId to lastBackupRead(row.bookId)
    }
    val importedRead = data.history.groupBy { it.bookKey }.mapValues { (_, views) -> views.maxOf { it.atEpochMs } }
    val importedProgress = data.progress.associateBy { it.bookId }
    var written = 0
    data.progress.forEach { row ->
        val existing = progressDao().get(row.bookId)
        val openingOnly = existing.isOpeningOnly()
        if (row != existing && (openingOnly || row.updatedAt > (existing?.updatedAt ?: -1))) {
            progressDao().upsert(row)
            changed += row.bookId
            written++
        }
    }
    data.bookPrefs.forEach { row ->
        val old = bookPrefsDao().get(row.bookId)
        val newTime = maxOf(importedProgress[row.bookId]?.updatedAt ?: -1, importedRead[row.bookId] ?: -1)
        if (old != row && (old == null || newTime > oldTimes.getValue(row.bookId))) {
            bookPrefsDao().upsert(row)
            changed += row.bookId
        }
    }
    val newestBookmarks = data.bookmarks.groupBy { it.bookId to it.pageIndex }.values
        .map { rows -> rows.maxBy { it.createdAt } }
    newestBookmarks.forEach { row ->
        if (row.createdAt > (dao.bookmark(row.bookId, row.pageIndex)?.createdAt ?: -1)) {
            bookmarkDao().add(row)
            changed += row.bookId
        }
    }
    val newViews = data.history.distinctBy { Triple(it.bookKey, it.page, it.atEpochMs) }
        .filter { !dao.hasView(it.bookKey, it.page, it.atEpochMs) }
    pageViewDao().record(newViews)
    changed += newViews.map { it.bookKey }
    val unmatched = data.favourites.filterNot { dao.hasBook(it) }.toSet()
    val applied = (data.favourites - unmatched).filter { dao.addFavourite(it) > 0 }
    changed += applied
    return ReadingChanges(changed, written, applied.size, unmatched)
}

private suspend fun AbsolutexDatabase.lastBackupRead(identity: String): Long =
    maxOf(progressDao().get(identity)?.updatedAt ?: -1, backupDao().lastRead(identity) ?: -1)

private fun ReadingProgress?.isOpeningOnly(): Boolean = this != null && pageIndex == 0 && updatedAt == 0L
