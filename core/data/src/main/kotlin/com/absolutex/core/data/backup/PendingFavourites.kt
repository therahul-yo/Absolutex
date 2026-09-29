package com.absolutex.core.data.backup

import androidx.room.withTransaction
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryDao
import com.absolutex.core.data.settings.DataStoreSettings
import com.absolutex.core.data.settings.clearPending
import com.absolutex.core.data.settings.pendingFavourites
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Serialises import and scan across their Room/DataStore phases, without a second DataStore file. */
@Singleton
class PendingFavourites @Inject constructor(
    private val db: AbsolutexDatabase,
    private val settings: DataStoreSettings,
) {
    internal val mutex = Mutex()

    suspend fun upsertScanned(dao: LibraryDao, books: List<LibraryBook>) = mutex.withLock {
        withContext(NonCancellable) {
            val pending = settings.pendingFavourites()
            val matched = books.map { it.contentKey }.toSet().intersect(pending)
            db.withTransaction {
                dao.upsertPreservingAddedAt(books)
                matched.forEach { db.backupDao().addFavourite(it) }
            }
            // A failed clear keeps the entries: retrying the scan cannot lose a favourite.
            if (matched.isNotEmpty()) settings.clearPending(matched)
        }
    }
}
