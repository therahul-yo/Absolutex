package com.absolutex.feature.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.LibraryRepository
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.core.scan.LibraryChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.Executor

/**
 * The live-update pipeline this PR is named for, driven end to end: [LibraryViewModel] wiring a
 * `watcherFactory` into a real [LibraryRepository] over a real in-memory Room database (matching
 * [RoomLibraryFeedTest]'s pattern). [LibraryRepository] is `final` with an `internal` constructor,
 * so nothing here subclasses or mocks it — every assertion reads back real persisted rows.
 *
 * Covers what [LibraryWatcherTest] does not: the location-management and fan-out logic living in
 * [LibraryViewModel] itself, not one watcher instance in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveLibraryUpdatesTest {

    @get:Rule val main = MainDispatcherRule()

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AbsolutexDatabase

    @Before
    fun setUp() {
        // Room's default query/transaction executors are real background threads, which race
        // against the test dispatcher's virtual clock: advanceUntilIdle() can return before a
        // background transaction has posted its resumption back. A same-thread executor makes
        // every DAO call finish before the suspend call returns, which is what advanceUntilIdle()
        // needs to see the write.
        val sameThread = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AbsolutexDatabase::class.java,
        ).setQueryExecutor(sameThread).setTransactionExecutor(sameThread).allowMainThreadQueries().build()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    private fun repository() = LibraryRepository(db.libraryDao())

    private fun bookFile(dir: File, name: String) = File(dir, name).apply { writeBytes(ByteArray(16)) }

    /** A settings source whose locations the test can change after the ViewModel is built. */
    private class FakePrefs(initial: AppPrefs) : AppPrefsSource {
        val state = MutableStateFlow(initial)
        override val appPrefs: Flow<AppPrefs> = state
        override suspend fun currentAppPrefs(): AppPrefs = state.value
    }

    private fun prefsOf(vararg locations: String) = FakePrefs(AppPrefs(locations = locations.toSet()))

    /** The ViewModel also needs a feed for its (unrelated) books listing; a stub is enough. */
    private object NoopFeed : LibraryFeed {
        override val capabilities = LibraryCapabilities.None
        override fun observeBooks(): Flow<List<LibraryBookUi>> = flowOf(emptyList())
        override suspend fun search(query: String): List<LibraryBookUi> = emptyList()
        override suspend fun setFavorite(paths: Set<String>, favorite: Boolean) = LibraryNotice.BatchFailed
        override suspend fun setRead(paths: Set<String>, read: Boolean) = LibraryNotice.BatchFailed
        override suspend fun delete(paths: Set<String>) = LibraryNotice.BatchFailed
    }

    @Test
    fun `each configured location gets exactly one live watcher, wired to the repository`() = test {
        val locA = tmp.newFolder("locA")
        val locB = tmp.newFolder("locB")
        val calls = mutableMapOf<File, Int>()
        val watcherFactory: (File) -> Flow<LibraryChange> = { root ->
            calls[root] = (calls[root] ?: 0) + 1
            flow { emit(LibraryChange.Added(bookFile(root, "Book 001.cbz").path)) }
        }
        LibraryViewModel(
            feed = NoopFeed,
            repository = repository(),
            prefs = prefsOf(locA.path, locB.path),
            watcherFactory = watcherFactory,
        )
        advanceUntilIdle()

        assertEquals("locA must get exactly one watcher", 1, calls[locA])
        assertEquals("locB must get exactly one watcher", 1, calls[locB])
        val stored = db.libraryDao().allOnce().map { it.path }
        assertTrue("locA's watched change must reach the repository", stored.any { it.startsWith(locA.path) })
        assertTrue("locB's watched change must reach the repository", stored.any { it.startsWith(locB.path) })
    }

    @Test
    fun `a locations change stops the old watcher instead of leaking it`() = test {
        val locA = tmp.newFolder("locA")
        val locB = tmp.newFolder("locB")
        val prefs = prefsOf(locA.path)
        val channels = mutableMapOf<File, MutableSharedFlow<LibraryChange>>()
        val watcherFactory: (File) -> Flow<LibraryChange> = { root ->
            channels.getOrPut(root) { MutableSharedFlow(extraBufferCapacity = 8) }
        }
        LibraryViewModel(
            feed = NoopFeed,
            repository = repository(),
            prefs = prefs,
            watcherFactory = watcherFactory,
        )
        advanceUntilIdle()

        // The user removes locA and adds locB from Settings.
        prefs.state.value = AppPrefs(locations = setOf(locB.path))
        advanceUntilIdle()

        // The old watcher for locA must be torn down: an event it reports now must not land.
        val ghost = bookFile(locA, "Ghost 001.cbz")
        channels.getValue(locA).emit(LibraryChange.Added(ghost.path))
        advanceUntilIdle()
        assertTrue(
            "a folder removed from Settings must stop applying its filesystem events",
            db.libraryDao().allOnce().none { it.path == ghost.path },
        )

        // The new watcher for locB must be live.
        val kept = bookFile(locB, "Kept 001.cbz")
        channels.getValue(locB).emit(LibraryChange.Added(kept.path))
        advanceUntilIdle()
        assertTrue(
            "the newly added folder's watcher must be live",
            db.libraryDao().allOnce().any { it.path == kept.path },
        )
    }

    @Test
    fun `events for one location are applied in the order the watcher emitted them`() = test {
        val loc = tmp.newFolder("loc")
        val path = File(loc, "Batman 001.cbz").path
        val watcherFactory: (File) -> Flow<LibraryChange> = {
            flow {
                File(path).writeBytes(ByteArray(16))
                emit(LibraryChange.Added(path))
                delay(10)
                File(path).delete()
                emit(LibraryChange.Removed(path))
            }
        }
        LibraryViewModel(
            feed = NoopFeed,
            repository = repository(),
            prefs = prefsOf(loc.path),
            watcherFactory = watcherFactory,
        )
        advanceUntilIdle()

        // Added then Removed, applied out of order, would leave the row behind.
        assertTrue(
            "the add and the remove must land in emission order",
            db.libraryDao().allOnce().none { it.path == path },
        )
    }

    @Test
    fun `a SAF location never gets a live filesystem watcher`() = test {
        var calls = 0
        val watcherFactory: (File) -> Flow<LibraryChange> = { calls++; flowOf() }
        LibraryViewModel(
            feed = NoopFeed,
            repository = repository(),
            prefs = prefsOf("content://com.absolutex.provider/tree/123"),
            watcherFactory = watcherFactory,
        )
        advanceUntilIdle()

        assertEquals("a content:// location must never reach the file watcher", 0, calls)
    }

    @Test
    fun `a throwing applyChange is logged and does not kill the collect loop`() = test {
        val loc = tmp.newFolder("loc")
        val channel = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 8)
        LibraryViewModel(
            feed = NoopFeed,
            repository = repository(),
            prefs = prefsOf(loc.path),
            watcherFactory = { channel },
        )
        advanceUntilIdle()

        val ok = bookFile(loc, "Ok 001.cbz")
        channel.emit(LibraryChange.Added(ok.path))
        advanceUntilIdle()
        assertTrue("the healthy event lands before the fault", db.libraryDao().allOnce().any { it.path == ok.path })

        // A closed database is a real, repeatable way to make the real repository throw.
        db.close()
        val bad1 = bookFile(loc, "Bad1.cbz")
        val bad2 = bookFile(loc, "Bad2.cbz")
        channel.emit(LibraryChange.Added(bad1.path))
        advanceUntilIdle()
        channel.emit(LibraryChange.Added(bad2.path))
        advanceUntilIdle()

        val failures = ShadowLog.getLogs()
            .count { it.tag == "LibraryViewModel" && it.msg.contains("live update failed") }
        assertEquals(
            "both post-fault events must still reach applyChange, proving the loop survived the first failure",
            2,
            failures,
        )
    }
}
