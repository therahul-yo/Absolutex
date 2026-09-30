package com.absolutex.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Before v9, page index 0 meant unread, including rows written by Mark unread. */
private const val LEGACY_VERSION = 8
private const val OPENED_VERSION = 9

object LegacyUnreadMigration : Migration(LEGACY_VERSION, OPENED_VERSION) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Keep the count learned by opening, even though the unread position is removed.
        db.execSQL(
            "UPDATE library_book SET pageCount = " +
                "(SELECT pageCount FROM reading_progress WHERE bookId = library_book.contentKey) " +
                "WHERE pageCount IS NULL AND contentKey IN " +
                "(SELECT bookId FROM reading_progress WHERE pageIndex = 0 AND pageCount > 0)",
        )
        db.execSQL("DELETE FROM reading_progress WHERE pageIndex = 0")
    }
}
