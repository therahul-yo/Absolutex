package com.absolutex.core.data.backup

import androidx.room.Dao
import androidx.room.Query
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.ReadingProgress

/** Backup reads and identity-based favourite writes; no schema changes. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM reading_progress")
    suspend fun progress(): List<ReadingProgress>

    @Query("SELECT * FROM bookmark")
    suspend fun bookmarks(): List<Bookmark>

    @Query("SELECT * FROM book_prefs")
    suspend fun bookPrefs(): List<BookPrefs>

    @Query("SELECT DISTINCT contentKey FROM library_book WHERE isFavorite = 1")
    suspend fun favourites(): List<String>

    @Query("UPDATE library_book SET isFavorite = 1 WHERE contentKey = :identity")
    suspend fun addFavourite(identity: String): Int
}
