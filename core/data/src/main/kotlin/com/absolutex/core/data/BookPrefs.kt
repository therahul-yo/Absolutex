package com.absolutex.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * What one book overrides for itself (§5.2's "per-book override plus global default").
 *
 * Null means "follow the global setting", which is the difference between a manga that must read
 * right to left and a reader who has simply not chosen for this book. Stored by BookIdentity, the
 * key progress, bookmarks and the library already share, so an override follows the book across
 * every route that opens it.
 *
 * Enums are held as their names, like the settings store: an ordinal silently re-points at a
 * different value the moment someone inserts a constant in the middle of the enum.
 */
@Entity(tableName = "book_prefs")
data class BookPrefs(
    @PrimaryKey val bookId: String,
    val readingFlow: String? = null,
    val pageLayout: String? = null,
    /** Exact anchor for reflowable EPUB progress: "spineIndex:charOffset" (§5.2, text EPUB reader). */
    val epubAnchor: String? = null,
)

@Dao
interface BookPrefsDao {
    @Query("SELECT * FROM book_prefs WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<BookPrefs?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prefs: BookPrefs)

    @Query("SELECT * FROM book_prefs WHERE bookId = :bookId")
    suspend fun get(bookId: String): BookPrefs?
}
