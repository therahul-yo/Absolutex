package com.absolutex.feature.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryDao
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The §5.1 "use original filename" switch at the place it takes effect: the label the library
 * shows and searches by. Room runs on the JVM here, mirroring :core:data's DAO tests, so the
 * assertions are about real persisted rows rather than a fake feed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLibraryFeedTest {

    private class FixedAppPrefs(prefs: AppPrefs) : AppPrefsSource {
        private val state = MutableStateFlow(prefs)
        override val appPrefs: Flow<AppPrefs> = state
        override suspend fun currentAppPrefs(): AppPrefs = state.value
    }

    private lateinit var db: AbsolutexDatabase
    private lateinit var dao: LibraryDao

    /** Parsed columns mirror what the scanner persisted for this filename. */
    private fun book(path: String) = LibraryBook(
        path = path,
        contentKey = "${path.substringAfterLast('/')}:100",
        series = "Absolute Batman",
        title = null,
        issue = 1.0,
        issueRaw = "1",
        volume = null,
        year = 2024,
        sizeBytes = 100,
        lastModified = 0,
        isImageFolder = false,
        pageCount = null,
        addedAt = 0,
        seenAtScan = 1,
    )

    private suspend fun insert(path: String) {
        dao.upsertAll(listOf(book(path)))
    }

    private fun feed(prefs: AppPrefs): RoomLibraryFeed =
        RoomLibraryFeed(
            repository = LibraryRepository(dao),
            progressDao = db.progressDao(),
            appPrefsSource = FixedAppPrefs(prefs),
        )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AbsolutexDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.libraryDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `the parsed label is the default display name`() = runTest {
        insert("/comics/Absolute Batman 001 (2024).cbr")
        val label = feed(AppPrefs()).observeBooks().first().single().displayName
        assertEquals("Absolute Batman #1", label)
    }

    @Test fun `the original filename switch shows the raw filename`() = runTest {
        insert("Absolute Batman 001 (2024).cbr")
        val label = feed(AppPrefs(useOriginalFilename = true))
            .observeBooks().first().single().displayName
        assertEquals("Absolute Batman 001 (2024).cbr", label)
    }

    @Test fun `search respects the original filename switch`() = runTest {
        insert("Absolute Batman 001 (2024).cbr")
        // The SQL matches series, title and path — the parse's columns plus the raw path, which
        // carries the original filename. So the raw name is searchable under either policy; what
        // the switch changes is the label the results come back with.
        val raw = feed(AppPrefs(useOriginalFilename = true)).search("Absolute Batman 001")
        assertEquals(1, raw.size)
        assertEquals("Absolute Batman 001 (2024).cbr", raw.single().displayName)
        val parsed = feed(AppPrefs()).search("Absolute Batman")
        assertEquals(1, parsed.size)
        assertEquals("Absolute Batman #1", parsed.single().displayName)
    }

    @Test fun `a progress row still joins under either policy`() = runTest {
        val path = "Absolute Batman 001 (2024).cbr"
        val b = book(path)
        insert(path)
        db.progressDao().upsert(ReadingProgress(b.contentKey, 4, 10, 0))
        val parsed = feed(AppPrefs()).observeBooks().first().single()
        val raw = feed(AppPrefs(useOriginalFilename = true)).observeBooks().first().single()
        assertEquals(4, parsed.currentPage)
        assertEquals(4, raw.currentPage)
    }
}
