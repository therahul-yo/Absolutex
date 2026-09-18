package com.absolutex.feature.reader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream

/**
 * ReaderViewModel.open()'s races, against a [FakeBookOpener] instead of a real archive or PDF:
 * dropping a reopen of the current book while a different one is in flight (review finding 7),
 * and leaking the just-opened handle when a cancel lands mid-open (finding 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    private fun vm(opener: BookOpener, progress: ProgressDao = FakeProgressDao()): ReaderViewModel {
        val settings = InMemorySettings()
        return ReaderViewModel(
            context = ApplicationProvider.getApplicationContext(),
            progressDao = progress,
            totalRamBytes = TOTAL_RAM_BYTES,
            prefs = settings,
            rendering = settings,
            appPrefs = settings,
            bookOpener = opener,
        )
    }

    private fun uri(name: String): Uri = Uri.parse("content://books/$name")

    private fun String.asIdentity() = "content://books/$this"

    @Test fun `reopening the current book while a different one is in flight is not dropped`() = test {
        val opener = FakeBookOpener()
        val vm = vm(opener)
        val bookA = uri("a")
        val bookB = uri("b")

        vm.open(bookA)
        advanceUntilIdle()
        assertEquals("a".asIdentity(), vm.ui.value.bookId)

        // B starts, and blocks before it can finish — the shared ReaderViewModel now has a
        // different book's open in flight.
        val gateB = opener.gate(bookB)
        vm.open(bookB)
        assertTrue(vm.ui.value.loading)

        // Reopening A must not be a silent no-op just because openedUri/source still describe A
        // from before B started: B's job must be cancelled and A actually reopened.
        vm.open(bookA)
        advanceUntilIdle()

        assertEquals("a".asIdentity(), vm.ui.value.bookId)
        assertTrue("must not still be waiting on B", !vm.ui.value.loading)
        gateB.complete(Unit) // tidy up; B's job was cancelled before it could ever await this
    }

    @Test fun `cancelling an in-flight open still closes the handle it had already opened`() = test {
        val opener = FakeBookOpener()
        val vm = vm(opener)
        val bookA = uri("a")
        val bookB = uri("b")

        val gateA = opener.gate(bookA)
        vm.open(bookA)
        advanceUntilIdle() // let A's open actually start and suspend on the gate
        assertTrue(vm.ui.value.loading)

        // Supersedes A before its gate is released: open() cancels A's job right away, but the
        // fake's own open() call — like the real openBook()/identityOf() it stands in for — is
        // wrapped in NonCancellable by the caller, so cancelling the job cannot interrupt it. It
        // only finishes once the gate is released, after the cancel already landed.
        vm.open(bookB)
        gateA.complete(Unit)
        advanceUntilIdle()

        val openedA = opener.opened.single { it.name == "a" }
        assertTrue("the superseded book's handle must be closed, not leaked", openedA.closed)
    }

    private companion object {
        const val TOTAL_RAM_BYTES = 4L * 1024 * 1024 * 1024
    }
}

/** A [BookOpener] whose completion can be held open per Uri, to land these races deterministically. */
private class FakeBookOpener : BookOpener {
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val opened = mutableListOf<FakeComicSource>()

    /** [open] for [uri] suspends until the returned gate is completed. */
    fun gate(uri: Uri): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[uri.toString()] = it }

    override suspend fun open(uri: Uri): Pair<Closeable, String> {
        gates[uri.toString()]?.await()
        val name = uri.lastPathSegment.orEmpty()
        val source = FakeComicSource(name)
        opened += source
        return source to uri.toString()
    }
}

private class FakeComicSource(val name: String) : ComicSource {
    var closed = false
        private set
    override val pages: List<Page> = listOf(Page(0, "page0.jpg"))
    override fun openPage(index: Int): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun close() {
        closed = true
    }
}

private class FakeProgressDao : ProgressDao {
    override suspend fun get(bookId: String): ReadingProgress? = null
    override fun observe(bookId: String): Flow<ReadingProgress?> = flowOf(null)
    override suspend fun upsert(progress: ReadingProgress) = Unit
    override suspend fun mostRecent(): ReadingProgress? = null
    override fun observeAll(): Flow<List<ReadingProgress>> = flowOf(emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
