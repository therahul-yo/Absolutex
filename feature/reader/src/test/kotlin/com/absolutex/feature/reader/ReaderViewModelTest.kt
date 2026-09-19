package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.BookIdentity
import com.absolutex.model.Page
import com.absolutex.remote.core.REMOTE_URI_SCHEME
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.source.ComicSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
import java.io.IOException
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

    private fun vm(
        opener: BookOpener,
        remote: RemoteBookOpener = FakeRemoteBookOpener(),
        progress: ProgressDao = FakeProgressDao(),
    ): ReaderViewModel {
        val settings = InMemorySettings()
        return ReaderViewModel(
            context = ApplicationProvider.getApplicationContext(),
            progressDao = progress,
            totalRamBytes = TOTAL_RAM_BYTES,
            prefs = settings,
            rendering = settings,
            appPrefs = settings,
            bookOpener = opener,
            remoteBookOpener = remote,
        )
    }

    private fun uri(name: String): Uri = Uri.parse("content://books/$name")

    private fun String.asIdentity() = "content://books/$this"

    private fun remoteUri(name: String): Uri = Uri.parse("$REMOTE_URI_SCHEME://nas/comics/$name")

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

    @Test
    fun `a 64 MiB cache preference yields a 64 MiB cache, not the RAM-derived floor`() = test {
        // The user-facing floor is AppPrefs.MIN_CACHE_MIB (64), deliberately below
        // MemoryBudget.FLOOR_BYTES (256): the latter bounds the RAM-derived default,
        // not a value the user picked (review finding 2 on #46).
        val settings = InMemorySettings()
        settings.updateApp { it.copy(cacheSizeMiB = 64) }
        val vm = ReaderViewModel(
            context = ApplicationProvider.getApplicationContext(),
            progressDao = FakeProgressDao(),
            totalRamBytes = TOTAL_RAM_BYTES,
            prefs = settings,
            rendering = settings,
            appPrefs = settings,
            bookOpener = FakeBookOpener(),
            remoteBookOpener = FakeRemoteBookOpener(),
        )
        vm.open(uri("a"))
        advanceUntilIdle()
        assertEquals(64L * 1024 * 1024, vm.tileCache.maxBytes().toLong())
    }

    @Test
    fun `a cache-size change mid-book resizes the live cache`() = test {
        val settings = InMemorySettings()
        val vm = ReaderViewModel(
            context = ApplicationProvider.getApplicationContext(),
            progressDao = FakeProgressDao(),
            totalRamBytes = TOTAL_RAM_BYTES,
            prefs = settings,
            rendering = settings,
            appPrefs = settings,
            bookOpener = FakeBookOpener(),
            remoteBookOpener = FakeRemoteBookOpener(),
        )
        vm.open(uri("a"))
        advanceUntilIdle()
        val before = vm.tileCache.maxBytes()
        settings.updateApp { it.copy(cacheSizeMiB = 1024) }
        advanceUntilIdle()
        assertEquals(1024L * 1024 * 1024, vm.tileCache.maxBytes().toLong())
        assertTrue("resize must actually change the budget", before != vm.tileCache.maxBytes())
    }

    @Test
    fun `upscaler state does not change identity when only colour changes`() = test {
        // RenderingPrefs is one data class: a colour edit re-emits a new instance. The
        // upscaler read must be distinctUntilChanged, or every colour change recomposes
        // the page slot through it (review finding 1 on #46). Asserted on the flow shape
        // the composable collects, without a recomposition-counting harness.
        val settings = InMemorySettings()
        val emissions = mutableListOf<Upscaler>()
        val job = launch {
            settings.renderingPrefs
                .map { it.upscaler }
                .distinctUntilChanged()
                .collect { emissions += it }
        }
        advanceUntilIdle() // let the collector start and receive the initial PLATFORM
        settings.updateRendering { it.copy(colour = ColourParams(brightness = 0.5f)) }
        settings.updateRendering { it.copy(colour = ColourParams(brightness = -0.5f)) }
        settings.updateRendering { it.copy(colour = ColourParams(contrast = 2f)) }
        settings.updateRendering { it.copy(upscaler = Upscaler.LANCZOS) }
        advanceUntilIdle()
        job.cancel()
        // Three colour changes emitted one upscaler value; only the real switch added one.
        assertEquals(listOf(Upscaler.PLATFORM, Upscaler.LANCZOS), emissions)
    }

    @Test fun `a remote uri opens through RemoteBookOpener and never touches OpenBook`() = test {
        val local = FakeBookOpener()
        val remote = FakeRemoteBookOpener()
        val vm = vm(local, remote)

        vm.open(remoteUri("book.cbz"))
        advanceUntilIdle()

        assertEquals(BookIdentity.of("book.cbz", FakeRemoteBookOpener.SIZE_BYTES), vm.ui.value.bookId)
        assertTrue("a remote uri must never reach OpenBook", local.opened.isEmpty())
    }

    @Test fun `a local uri still goes through OpenBook, untouched by RemoteBookOpener`() = test {
        val local = FakeBookOpener()
        val remote = FakeRemoteBookOpener()
        val vm = vm(local, remote)

        vm.open(uri("a"))
        advanceUntilIdle()

        assertEquals("a".asIdentity(), vm.ui.value.bookId)
        assertTrue("a local uri must never reach RemoteBookOpener", remote.opened.isEmpty())
    }

    @Test fun `a superseded remote open closes the first result`() = test {
        val remote = FakeRemoteBookOpener()
        val vm = vm(FakeBookOpener(), remote)
        val bookA = remoteUri("a.cbz")
        val bookB = remoteUri("b.cbz")

        remote.gate(bookA.toString())
        vm.open(bookA)
        advanceUntilIdle() // A is stuck mid "network read", same shape as a live SMB/FTP call

        // Supersedes A before its gate is released: open() cancels A's job right away, and
        // (unlike the local path) that is a real cancel — RemoteBookOpener.open is a genuine
        // suspend fun with no NonCancellable wrapper, so it lands while A is suspended on the
        // gate. FakeRemoteBookOpener closes what it had already built before rethrowing, the
        // same contract the production binding (RemoteModule) upholds.
        vm.open(bookB)
        advanceUntilIdle()

        val openedA = remote.opened.single { it.name == "a.cbz" }
        assertTrue("the superseded remote result must be closed, not leaked", openedA.closed)
        assertEquals(BookIdentity.of("b.cbz", FakeRemoteBookOpener.SIZE_BYTES), vm.ui.value.bookId)
    }

    @Test fun `a cancelled remote open closes what it opened`() = test {
        val local = FakeBookOpener()
        val remote = FakeRemoteBookOpener()
        val vm = vm(local, remote)
        val bookA = remoteUri("a.cbz")
        val bookB = uri("b")

        remote.gate(bookA.toString())
        vm.open(bookA)
        advanceUntilIdle() // A is stuck mid "network read"

        // A switch to an entirely different (local) book cancels A's job the same way any other
        // supersede does — the reader has one openJob, not one per opener — and that cancel
        // reaches FakeRemoteBookOpener directly, with no NonCancellable in the way.
        vm.open(bookB)
        advanceUntilIdle()

        val openedA = remote.opened.single { it.name == "a.cbz" }
        assertTrue("the cancelled remote open's transport must be closed, not leaked", openedA.closed)
        assertEquals("b".asIdentity(), vm.ui.value.bookId)
    }

    @Test fun `a remote IOException surfaces the generic error and never the host or path`() = test {
        val remote = FakeRemoteBookOpener()
        remote.failure = IOException(
            "smb://admin:hunter2@192.168.1.50/private/secret-diary.cbz: connection refused",
        )
        val vm = vm(FakeBookOpener(), remote)

        vm.open(remoteUri("secret-diary.cbz"))
        advanceUntilIdle()

        val expected = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.reader_open_failed)
        val error = vm.ui.value.error
        assertEquals(expected, error)
        assertTrue("must never leak the host", error?.contains("192.168.1.50") != true)
        assertTrue("must never leak the path", error?.contains("secret-diary") != true)
        assertTrue("must never leak a credential", error?.contains("hunter2") != true)
    }

    @Test fun `a remote book's identity matches the same file opened locally`() = test {
        val name = "Watchmen 01.cbz"
        val identity = BookIdentity.of(name, FakeRemoteBookOpener.SIZE_BYTES)

        val localVm = vm(BookOpener { _ -> FakeComicSource(name) to identity })
        localVm.open(uri(name))
        advanceUntilIdle()

        val remoteVm = vm(FakeBookOpener(), FakeRemoteBookOpener())
        remoteVm.open(remoteUri(name))
        advanceUntilIdle()

        assertEquals(identity, localVm.ui.value.bookId)
        assertEquals(localVm.ui.value.bookId, remoteVm.ui.value.bookId)
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

/**
 * A [RemoteBookOpener] whose completion can be held open per Uri, mirroring [FakeBookOpener].
 *
 * Unlike [FakeBookOpener], [ReaderViewModel] does not wrap this call in `NonCancellable` — a
 * cancel reaching [open] while it awaits a gate is a real [CancellationException], and [open]
 * closes the source it already built before rethrowing, exactly like the production binding
 * ([RemoteModule] in `:feature:remote`) closes the [RemoteOpenResult] its own dispatcher switch
 * can otherwise discard on a cancel. This is the contract [RemoteBookOpener.open]'s own KDoc
 * means by "the reader calls it ... same as every other open path": the reader trusts it,
 * rather than defeating cancellation for it the way it must for the blocking local path.
 */
private class FakeRemoteBookOpener : RemoteBookOpener {
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val opened = mutableListOf<FakeComicSource>()
    var failure: IOException? = null

    /** [open] for [uri] suspends until the returned gate is completed. */
    fun gate(uri: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[uri] = it }

    override suspend fun open(uri: String): RemoteOpenResult {
        val displayName = uri.substringAfterLast('/')
        val source = FakeComicSource(displayName)
        opened += source
        try {
            gates[uri]?.await()
        } catch (e: CancellationException) {
            source.close()
            throw e
        }
        failure?.let { throw it }
        return RemoteOpenResult.Ready(source, BookIdentity.of(displayName, SIZE_BYTES), displayName, SIZE_BYTES)
    }

    companion object {
        const val SIZE_BYTES = 4096L
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
