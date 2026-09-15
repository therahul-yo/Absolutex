package com.absolutex.feature.widget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room runs on the JVM through Robolectric (:core:data pattern), so the source is covered device-free. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomContinueReadingSourceTest {

    private lateinit var db: AbsolutexDatabase
    private lateinit var source: RoomContinueReadingSource

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        source = RoomContinueReadingSource(db.progressDao(), db.libraryDao())
    }

    @After fun tearDown() = db.close()

    private fun book(path: String, contentKey: String, title: String, series: String) = LibraryBook(
        path = path,
        contentKey = contentKey,
        series = series,
        title = title,
        issue = 1.0,
        issueRaw = "1",
        volume = null,
        year = null,
        sizeBytes = 100,
        lastModified = 0,
        isImageFolder = false,
        pageCount = 45,
        addedAt = 0,
        seenAtScan = 1,
    )

    @Test fun `empty database yields no models`() = runTest {
        assertEquals(emptyList<WidgetModel>(), source.inProgress(WIDGET_MAX_ITEMS))
    }

    @Test fun `zero limit yields no models even with progress stored`() = runTest {
        db.progressDao().upsert(ReadingProgress("Batman 001.cbz:100", 12, 45, 1_700_000_000_000))
        assertEquals(emptyList<WidgetModel>(), source.inProgress(0))
    }

    @Test fun `most recent progress maps with its library title`() = runTest {
        db.libraryDao().upsertAll(
            listOf(book("/sd/Comics/Batman 001.cbz", "Batman 001.cbz:100", "The Long Halloween", "Batman")),
        )
        db.progressDao().upsert(ReadingProgress("Batman 001.cbz:100", 12, 45, 1_700_000_000_000))
        val models = source.inProgress(WIDGET_MAX_ITEMS)
        assertEquals(1, models.size)
        assertEquals("Batman 001.cbz:100", models[0].bookId)
        assertEquals("The Long Halloween", models[0].title)
        assertEquals(13f / 45f, models[0].progressFraction, 0.0001f)
        assertNull(models[0].coverKey)
    }

    @Test fun `title falls back to the book id without a library match`() = runTest {
        db.progressDao().upsert(ReadingProgress("orphan.cbz:7", 0, 10, 1_700_000_000_000))
        assertEquals("orphan.cbz:7", source.inProgress(WIDGET_MAX_ITEMS).single().title)
    }

    @Test fun `a limit past the cap still returns the single stored row`() = runTest {
        db.progressDao().upsert(ReadingProgress("orphan.cbz:7", 0, 10, 1_700_000_000_000))
        assertEquals(1, source.inProgress(99).size)
    }

    @Test fun `library path resolves for the tap deep link`() = runTest {
        db.libraryDao().upsertAll(
            listOf(book("/sd/Comics/Batman 001.cbz", "Batman 001.cbz:100", "The Long Halloween", "Batman")),
        )
        assertEquals("/sd/Comics/Batman 001.cbz", source.libraryPathFor("Batman 001.cbz:100"))
    }

    @Test fun `library path is null when the book left the library`() = runTest {
        assertNull(source.libraryPathFor("gone.cbz:1"))
    }
}
