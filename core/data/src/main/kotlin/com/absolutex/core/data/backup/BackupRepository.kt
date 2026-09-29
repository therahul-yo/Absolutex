package com.absolutex.core.data.backup

import androidx.room.withTransaction
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.settings.DataStoreSettings
import com.absolutex.core.data.settings.backupPreferences
import com.absolutex.core.data.settings.pendingFavouriteAges
import com.absolutex.core.data.settings.pendingFavourites
import com.absolutex.core.data.settings.restoreBackup
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    private val db: AbsolutexDatabase,
    private val settings: DataStoreSettings,
    private val pending: PendingFavourites,
) {
    /** Call on IO; prepare the entire bounded export before opening/truncating the selected document. */
    suspend fun export(appVersion: String): ExportResult = pending.mutex.withLock {
        val preferences = settings.backupPreferences()
        val favourites = settings.pendingFavouriteAges()
        db.withTransaction { db.buildExport(appVersion, favourites, preferences) }
    }

    /** Validation finishes before any write. A full pending set never blocks a valid import. */
    suspend fun restore(input: InputStream, now: Long = System.currentTimeMillis()): RestoreResult {
        val data = BackupCodec.read(input, now)
        return pending.mutex.withLock {
            val oldPending = settings.pendingFavourites()
            withContext(NonCancellable) {
                val reading = db.withTransaction {
                    db.mergeBackup(data.copy(favourites = data.favourites + oldPending))
                }
                try {
                    val stored = settings.restoreBackup(data.preferences, reading.unmatched, now)
                    RestoreResult.Complete(
                        (reading.books + stored.addedPending).size, reading.progress,
                        reading.favourites + stored.addedPending.size, stored.changed, stored.dropped,
                    )
                } catch (_: Exception) {
                    RestoreResult.ReadingDataOnly(reading.books.size, reading.progress, reading.favourites)
                }
            }
        }
    }
}
