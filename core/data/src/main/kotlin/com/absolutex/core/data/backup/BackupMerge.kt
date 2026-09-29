package com.absolutex.core.data.backup

import com.absolutex.core.data.AbsolutexDatabase

/** Invoked only inside the caller's Room transaction. Existing data is never deleted. */
internal suspend fun AbsolutexDatabase.mergeBackup(data: BackupData): Set<String> {
    val dao = backupDao()
    val progress = dao.progress().associateBy { it.bookId }
    val history = pageViewDao().all()
    val currentRead = history.groupBy { it.bookKey }.mapValues { (_, views) -> views.maxOf { it.atEpochMs } }
    val importedRead = data.history.groupBy { it.bookKey }.mapValues { (_, views) -> views.maxOf { it.atEpochMs } }
    val importedProgress = data.progress.associateBy { it.bookId }
    data.progress.forEach { row ->
        if (row.updatedAt > (progress[row.bookId]?.updatedAt ?: -1)) progressDao().upsert(row)
    }
    val existingPrefs = dao.bookPrefs().associateBy { it.bookId }
    data.bookPrefs.forEach { row ->
        val oldTime = maxOf(progress[row.bookId]?.updatedAt ?: -1, currentRead[row.bookId] ?: -1)
        val newTime = maxOf(importedProgress[row.bookId]?.updatedAt ?: -1, importedRead[row.bookId] ?: -1)
        if (row.bookId !in existingPrefs || newTime > oldTime) bookPrefsDao().upsert(row)
    }
    val bookmarks = dao.bookmarks().associateBy { it.bookId to it.pageIndex }
    data.bookmarks.groupBy { it.bookId to it.pageIndex }.values.map { rows -> rows.maxBy { it.createdAt } }.forEach { row ->
        if (row.createdAt > (bookmarks[row.bookId to row.pageIndex]?.createdAt ?: -1)) bookmarkDao().add(row)
    }
    val existingViews = history.map { Triple(it.bookKey, it.page, it.atEpochMs) }.toSet()
    val newViews = data.history.distinctBy { Triple(it.bookKey, it.page, it.atEpochMs) }
        .filter { Triple(it.bookKey, it.page, it.atEpochMs) !in existingViews }
    pageViewDao().record(newViews)
    return data.favourites.filter { dao.addFavourite(it) == 0 }.toSet()
}
