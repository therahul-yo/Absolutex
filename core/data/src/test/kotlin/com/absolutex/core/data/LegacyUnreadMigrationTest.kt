package com.absolutex.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyUnreadMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seedV8(): File {
        val file = File(tmp.root, "legacy.db")
        val schema = JSONObject(File("schemas/com.absolutex.core.data.AbsolutexDatabase/8.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO reading_progress VALUES ('unread:1', 0, 20, 100), ('unknown:1', 0, 0, 100)")
            db.execSQL("INSERT INTO reading_progress VALUES ('read:1', 4, 20, 150)")
            db.execSQL("INSERT INTO library_book " +
                "(path, contentKey, sizeBytes, lastModified, isImageFolder, addedAt, seenAtScan) " +
                "VALUES ('/unread.cbz', 'unread:1', 1, 1, 0, 1, 1)")
            db.execSQL("INSERT INTO bookmark (bookId, pageIndex, createdAt) VALUES ('read:1', 4, 150)")
            db.execSQL("INSERT INTO book_prefs (bookId) VALUES ('read:1')")
            db.execSQL("INSERT INTO page_view (bookKey, page, atEpochMs) VALUES ('read:1', 4, 150)")
            db.version = 8
        }
        return file
    }

    private fun open(file: File) = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AbsolutexDatabase::class.java, file.path,
    ).addMigrations(LegacyUnreadMigration).allowMainThreadQueries().build()

    @Test fun `upgrade clears only legacy unread rows and keeps counts and other reading data`() = runTest {
        val db = open(seedV8())
        try {
            assertNull(db.progressDao().get("unread:1"))
            assertNull(db.progressDao().get("unknown:1"))
            assertEquals(ReadingProgress("read:1", 4, 20, 150), db.progressDao().get("read:1"))
            assertEquals(20, db.libraryDao().allOnce().single().pageCount)
            assertEquals(1, db.bookmarkDao().pages("read:1").first().size)
            assertEquals("read:1", db.bookPrefsDao().get("read:1")?.bookId)
            assertEquals(1, db.pageViewDao().count())
        } finally { db.close() }
    }

    @Test fun `cleanup runs once and never deletes a page-one opening after upgrade`() = runTest {
        val file = seedV8()
        val upgraded = open(file)
        try { upgraded.progressDao().recordOpened("new:1", 20, 200) } finally { upgraded.close() }
        val reopened = open(file)
        try { assertEquals(0, reopened.progressDao().get("new:1")?.pageIndex) } finally { reopened.close() }
    }
}
