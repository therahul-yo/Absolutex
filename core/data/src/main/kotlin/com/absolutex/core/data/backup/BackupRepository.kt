package com.absolutex.core.data.backup

import androidx.room.withTransaction
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.settings.DataStoreSettings
import com.absolutex.core.data.settings.backupPreferences
import com.absolutex.core.data.settings.pendingFavourites
import com.absolutex.core.data.settings.restoreBackup
import com.absolutex.core.data.settings.validatePending
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
    suspend fun export(appVersion: String): ByteArray = pending.mutex.withLock {
        val preferences = settings.backupPreferences()
        val favourites = settings.pendingFavourites()
        val data = db.withTransaction {
            val dao = db.backupDao()
            BackupData(appVersion, dao.progress(), db.pageViewDao().all(), dao.bookmarks(), dao.bookPrefs(),
                dao.favourites().toSet() + favourites, preferences)
        }
        BackupWriter.write(data)
    }

    /** Validation finishes before any write, including pending-set capacity validation. */
    suspend fun restore(input: InputStream): RestoreResult {
        val data = BackupCodec.read(input)
        return pending.mutex.withLock {
            val oldPending = settings.pendingFavourites()
            validatePending(oldPending + data.favourites)
            withContext(NonCancellable) {
                val unmatched = db.withTransaction {
                    db.mergeBackup(data.copy(favourites = data.favourites + oldPending))
                }
                try {
                    settings.restoreBackup(data.preferences, unmatched)
                    RestoreResult.Complete(data.bookIds.size)
                } catch (_: Exception) {
                    RestoreResult.ReadingDataOnly(data.bookIds.size)
                }
            }
        }
    }
}
