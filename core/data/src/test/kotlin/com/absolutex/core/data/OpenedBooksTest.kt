package com.absolutex.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.model.BookIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A file opened on its own becomes a library row, keyed the way the reader keys its progress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenedBooksTest {

    private lateinit var db: AbsolutexDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private val path = "content://docs/document/primary%3ABatman%20001.cb7"

    @Test fun `a book type becomes a row with the reader's identity`() {
        val row = openedBookRow(path, "Batman 001 (2024).cb7", 1234L, now = 5L)!!
        assertEquals(BookIdentity.of("Batman 001 (2024).cb7", 1234L), row.contentKey)
        assertEquals("cb7", row.format)
        assertEquals("Batman 001 (2024).cb7", row.fileName)
        assertEquals(path, row.path)
        assertFalse(row.isImageFolder)
    }

    @Test fun `every listed container type is accepted, in any case`() {
        listOf("a.CBZ", "a.cbr", "a.cb7", "a.cbt", "a.zip", "a.rar", "a.7z", "a.tar", "a.pdf", "a.epub").forEach {
            assertNotNull("$it should be recorded", openedBookRow(path, it, 1L, now = 1L))
        }
    }

    @Test fun `a file that is not a book type is not recorded`() {
        assertNull(openedBookRow(path, "notes.txt", 10L, now = 1L))
        assertNull(openedBookRow(path, "no-extension", 10L, now = 1L))
    }

    @Test fun `an unknown size falls back to the path, as the reader does`() {
        val row = openedBookRow(path, "a.cbz", null, now = 1L)!!
        assertEquals(path, row.contentKey)
        assertEquals(0L, row.sizeBytes)
    }

    @Test fun `recording writes the row and keeps a favourite across a second open`() = runBlocking {
        val opened = OpenedBooks(db.libraryDao())
        assertTrue(opened.record(path, "Batman 001.cbz", 100L))
        db.libraryDao().updateFavorite(path, true)

        assertTrue(opened.record(path, "Batman 001.cbz", 100L))
        val rows = db.libraryDao().allOnce()
        assertEquals(1, rows.size)
        assertTrue("reopening must not clear a favourite", rows.single().isFavorite)
    }

    @Test fun `recording a non-book writes nothing`() = runBlocking {
        assertFalse(OpenedBooks(db.libraryDao()).record(path, "notes.txt", 1L))
        assertTrue(db.libraryDao().allOnce().isEmpty())
    }
}
