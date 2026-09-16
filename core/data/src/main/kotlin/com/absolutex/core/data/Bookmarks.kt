package com.absolutex.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A bookmarked page (§5.2). Keyed by [bookId], the same BookIdentity progress and the library use,
 * so a bookmark follows its book whichever route opened it.
 */
@Entity(tableName = "bookmark", primaryKeys = ["bookId", "pageIndex"])
data class Bookmark(
    val bookId: String,
    val pageIndex: Int,
    /** Epoch millis, for last-write-wins sync like progress (§5.5). */
    val createdAt: Long,
)

@Dao
interface BookmarkDao {
    @Query("SELECT pageIndex FROM bookmark WHERE bookId = :bookId ORDER BY pageIndex")
    fun pages(bookId: String): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: Bookmark)

    /** Returns rows removed, so a toggle knows whether there was anything to remove. */
    @Query("DELETE FROM bookmark WHERE bookId = :bookId AND pageIndex = :pageIndex")
    suspend fun remove(bookId: String, pageIndex: Int): Int
}
