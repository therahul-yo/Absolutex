package com.absolutex.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey val bookId: String,
    val pageIndex: Int,
    val pageCount: Int,
    /** Epoch millis. §5.5 sync is last-write-wins, so every row carries its own timestamp. */
    val updatedAt: Long,
)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingProgress?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ReadingProgress?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgress)

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ReadingProgress?
}

@Database(entities = [ReadingProgress::class], version = 1, exportSchema = true)
abstract class AbsolutexDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
}
