package com.absolutex.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavouritesMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seedV9(): File {
        val file = File(tmp.root, "favourites.db")
        val schema = JSONObject(File("schemas/com.absolutex.core.data.AbsolutexDatabase/9.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO library_book " +
                "(path, contentKey, sizeBytes, lastModified, isImageFolder, addedAt, seenAtScan, isFavorite) VALUES " +
                "('/a/one.cbz', 'one.cbz:16', 16, 0, 0, 10, 1, 1), " +
                "('/b/one.cbz', 'one.cbz:16', 16, 0, 0, 20, 1, 0), " +
                "('/a/two.cbz', 'two.cbz:16', 16, 0, 0, 30, 1, 0), " +
                "('/a/three.cbz', 'three.cbz:16', 16, 0, 0, 40, 1, 1), " +
                "('/b/three.cbz', 'three.cbz:16', 16, 0, 0, 50, 1, 1)")
            db.execSQL("INSERT INTO reading_progress VALUES ('one.cbz:16', 4, 20, 100)")
            db.execSQL("INSERT INTO bookmark VALUES ('one.cbz:16', 4, 100)")
            db.version = 9
        }
        return file
    }

    private fun open(file: File) = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AbsolutexDatabase::class.java, file.path,
    ).addMigrations(FavouritesMigration).allowMainThreadQueries().build()

    @Test fun `upgrade preserves and deduplicates favourites and survives removal and reopening`() = runTest {
        val file = seedV9()
        val upgraded = open(file)
        try {
            assertEquals(setOf("one.cbz:16", "three.cbz:16"), upgraded.backupDao().favourites().toSet())
            val books = upgraded.libraryDao().allOnce()
            assertTrue(books.filter { it.contentKey == "one.cbz:16" }.all { it.isFavorite })
            assertFalse(books.single { it.contentKey == "two.cbz:16" }.isFavorite)
            assertEquals(4, upgraded.progressDao().get("one.cbz:16")?.pageIndex)
            assertEquals(1, upgraded.backupDao().bookmarks().size)
            upgraded.libraryDao().clear()
        } finally { upgraded.close() }
        val reopened = open(file)
        try {
            assertEquals(setOf("one.cbz:16", "three.cbz:16"), reopened.backupDao().favourites().toSet())
            assertTrue(reopened.libraryDao().allOnce().isEmpty())
            assertEquals(4, reopened.progressDao().get("one.cbz:16")?.pageIndex)
        } finally { reopened.close() }
    }
}
