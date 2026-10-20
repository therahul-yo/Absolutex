package com.absolutex.core.data.backup

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.absolutex.core.data.FavouriteQueries
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.OPENING_PAGE_MARKER
import com.absolutex.core.data.ReadingProgress

/** Backup reads and identity-based favourite writes. */
@Dao
interface BackupDao : FavouriteQueries {
    @Query("SELECT * FROM reading_progress")
    suspend fun progress(): List<ReadingProgress>

    @Query("SELECT * FROM bookmark")
    suspend fun bookmarks(): List<Bookmark>

    @Query("SELECT * FROM book_prefs")
    suspend fun bookPrefs(): List<BookPrefs>

    @Query("SELECT contentKey FROM book_favourite")
    suspend fun favourites(): List<String>

    @Transaction
    suspend fun addFavourite(identity: String): Int = setFavourite(identity, true)
    @Query("SELECT EXISTS(SELECT 1 FROM library_book WHERE contentKey = :identity)")
    suspend fun hasBook(identity: String): Boolean

    @Query("SELECT MAX(atEpochMs) FROM page_view WHERE page != $OPENING_PAGE_MARKER AND bookKey = :identity")
    suspend fun lastRead(identity: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM page_view WHERE bookKey = :identity AND page = :page AND atEpochMs = :time)")
    suspend fun hasView(identity: String, page: Int, time: Long): Boolean

    @Query("SELECT * FROM bookmark WHERE bookId = :identity AND pageIndex = :page")
    suspend fun bookmark(identity: String, page: Int): Bookmark?
}
