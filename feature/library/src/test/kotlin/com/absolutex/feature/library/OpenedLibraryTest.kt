package com.absolutex.feature.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.OpenedBooks
import com.absolutex.core.data.settings.InMemorySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenedLibraryTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    private lateinit var db: AbsolutexDatabase
    private val prefs = InMemorySettings()
    private val book = LibraryBook(
        path = "/comics/a.cbz", contentKey = "a.cbz:100", series = "a", title = null,
        issue = null, issueRaw = null, volume = null, year = null, sizeBytes = 100,
        lastModified = 1, isImageFolder = false, pageCount = null, addedAt = 1, seenAtScan = 1,
        format = "cbz", fileName = "a.cbz",
    )

    @Before fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java,
        ).setQueryExecutor(direct).setTransactionExecutor(direct).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun feed() = RoomLibraryFeed(
        LibraryRepository(db.libraryDao()), db.progressDao(), db.bookFactsDao(), db.pageViewDao(), prefs,
    )

    private suspend fun awaitBooks(vm: LibraryViewModel, predicate: (List<LibraryBookUi>) -> Boolean): LibraryUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.ui.first { predicate(it.allBooks) } } }

    @Test fun `picker and library openings at page one appear in Recent and are ongoing`() = runTest(main.dispatcher) {
        for (picked in listOf(false, true)) {
            if (picked) OpenedBooks(db.libraryDao()).record(book.path, book.fileName, book.sizeBytes)
            else db.libraryDao().upsertAll(listOf(book))
            db.progressDao().recordOpened(book.contentKey, 20, 100)
            val ui = feed().observeBooks().first().single()
            assertEquals(0, ui.currentPage)
            assertEquals(ReadState.IN_PROGRESS, ui.readState)
            val recent = LibraryUiState.Initial.copy(allBooks = listOf(ui), section = HomeSection.RECENT).recomputed()
            assertEquals(listOf(ui), recent.visibleBooks)
            assertEquals(listOf(ui), listOf(ui).continueReadingBooks())
            assertEquals(100L, ui.lastReadAt)
            assertEquals(if (picked) 2 else 1, db.pageViewDao().count())
        }
    }

    @Test fun `Mark unread removes the opening position but preserves the known count`() = runTest(main.dispatcher) {
        db.libraryDao().upsertAll(listOf(book))
        db.progressDao().recordOpened(book.contentKey, 20, 100)
        assertEquals(LibraryNotice.BatchApplied(1, 0), feed().setRead(setOf(book.path), false))
        val ui = feed().observeBooks().first().single()
        assertEquals(ReadState.UNREAD, ui.readState)
        assertNull(ui.currentPage)
        assertNull(ui.lastReadAt)
        assertEquals(emptyList<LibraryBookUi>(), listOf(ui).continueReadingBooks())
        assertEquals(20, ui.pageCount)
        assertNull(db.progressDao().get(book.contentKey))
        feed().setRead(setOf(book.path), true)
        assertEquals(19, db.progressDao().get(book.contentKey)?.pageIndex)
    }

    @Test fun `a learned cover count refreshes the active card`() = runTest(main.dispatcher) {
        db.libraryDao().upsertAll(listOf(book))
        val vm = LibraryViewModel(feed(), LibraryRepository(db.libraryDao()), prefs)
        assertNull(awaitBooks(vm) { it.size == 1 }.visibleBooks.single().pageCount)
        db.bookFactsDao().updatePageCount(book.path, 42)
        val refreshed = awaitBooks(vm) { it.singleOrNull()?.pageCount == 42 }
        assertEquals(42, refreshed.visibleBooks.single().pageCount)
    }

    @Test fun `search cards refresh counts without adding other search matches`() = runTest(main.dispatcher) {
        db.libraryDao().upsertAll(listOf(book, book.copy(path = "/comics/b.cbz", contentKey = "b:100", series = "b")))
        val vm = LibraryViewModel(feed(), LibraryRepository(db.libraryDao()), prefs)
        awaitBooks(vm) { it.size == 2 }
        vm.onQueryChange("a.cbz")
        advanceUntilIdle()
        awaitBooks(vm) { it.size == 1 }
        db.bookFactsDao().updatePageCount(book.path, 42)
        val refreshed = awaitBooks(vm) { it.size == 1 && it.single().pageCount == 42 }
        assertEquals(book.path, refreshed.visibleBooks.single().path)
        assertEquals(42, refreshed.visibleBooks.single().pageCount)
    }

    @Test fun `a copied finished book keeps its count and finished status`() = runTest(main.dispatcher) {
        db.libraryDao().upsertAll(listOf(book.copy(pageCount = 42)))
        db.progressDao().upsert(com.absolutex.core.data.ReadingProgress(book.contentKey, 41, 42, 100))
        db.libraryDao().upsertPreservingAddedAt(listOf(book.copy(lastModified = 200)))
        val ui = feed().observeBooks().first().single()
        assertEquals(42, ui.pageCount)
        assertEquals(ReadState.FINISHED, ui.readState)
    }
}
