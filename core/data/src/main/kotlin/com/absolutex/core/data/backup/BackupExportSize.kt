package com.absolutex.core.data.backup

/** Measure actual serialized bytes; shrink only after an exact byte/value/identity guard fails. */
internal fun BackupData.writeSizedExport(): Pair<ByteArray, BackupData> {
    var remaining = this
    repeat(MAX_SIZE_ATTEMPTS) {
        try {
            return BackupWriter.write(remaining) to remaining
        } catch (_: BackupCapacityExceeded) {
            remaining = remaining.shrinkOldest()
        }
    }
    error("Backup size retry bound exceeded")
}

/** Lists arrive newest first. Halving bounds retries while retaining the newest rows in each tier. */
private fun BackupData.shrinkOldest(): BackupData = when {
    history.isNotEmpty() -> copy(history = history.take(history.size / 2))
    bookmarks.isNotEmpty() -> copy(bookmarks = bookmarks.take(bookmarks.size / 2))
    favourites.isNotEmpty() -> copy(favourites = favourites.take(favourites.size / 2).toSet())
    bookPrefs.isNotEmpty() -> copy(bookPrefs = bookPrefs.take(bookPrefs.size / 2))
    progress.isNotEmpty() -> copy(progress = progress.take(progress.size / 2))
    else -> error("Settings-only backup exceeds capacity")
}

// Five bounded tiers of at most 12,000 rows take at most 14 halvings each, plus the final write.
private const val MAX_SIZE_ATTEMPTS = 75
