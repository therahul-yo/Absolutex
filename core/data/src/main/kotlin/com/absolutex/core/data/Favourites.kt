package com.absolutex.core.data

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** User data, independent of the locations that happen to contain a book. */
@Entity(tableName = "book_favourite")
data class BookFavourite(@PrimaryKey val contentKey: String, val addedAt: Long)

/** Shared by the library, reader and backup DAOs so every toggle updates both tables atomically. */
interface FavouriteQueries {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun rememberFavourite(favourite: BookFavourite): Long

    @Query("DELETE FROM book_favourite WHERE contentKey = :identity")
    suspend fun forgetFavourite(identity: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM book_favourite WHERE contentKey = :identity)")
    suspend fun hasFavourite(identity: String): Boolean

    @Query("SELECT MAX(addedAt) FROM library_book WHERE contentKey = :identity")
    suspend fun favouriteOrigin(identity: String): Long?

    @Query("UPDATE library_book SET isFavorite = :favourite WHERE contentKey = :identity AND isFavorite != :favourite")
    suspend fun updateFavouriteRows(identity: String, favourite: Boolean): Int

    @Transaction
    suspend fun setFavourite(identity: String, favourite: Boolean): Int {
        if (favourite) {
            val origin = favouriteOrigin(identity) ?: return 0
            rememberFavourite(BookFavourite(identity, origin))
        } else {
            forgetFavourite(identity)
        }
        return updateFavouriteRows(identity, favourite)
    }
}

/** Copy every existing favourite before scans or folder removal can discard its library row. */
object FavouritesMigration : Migration(FAVOURITES_FROM_VERSION, FAVOURITES_VERSION) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS book_favourite " +
                "(contentKey TEXT NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO book_favourite (contentKey, addedAt) " +
                "SELECT contentKey, MAX(addedAt) FROM library_book WHERE isFavorite = 1 GROUP BY contentKey",
        )
        // A favourite belongs to the identity even when old path-based toggles disagreed.
        db.execSQL("UPDATE library_book SET isFavorite = 1 WHERE contentKey IN (SELECT contentKey FROM book_favourite)")
    }
}

private const val FAVOURITES_FROM_VERSION = 9
private const val FAVOURITES_VERSION = 10
