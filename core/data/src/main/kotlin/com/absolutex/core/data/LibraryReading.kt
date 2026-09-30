package com.absolutex.core.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** Mark unread keeps the learned count and clears the position as one change. */
@Singleton
class LibraryReading @Inject constructor(private val db: AbsolutexDatabase) {
    suspend fun markUnread(identity: String, count: Int) {
        db.withTransaction {
            if (count > 0) db.bookFactsDao().retainCountByIdentity(identity, count)
            db.progressDao().clear(identity)
        }
    }
}
