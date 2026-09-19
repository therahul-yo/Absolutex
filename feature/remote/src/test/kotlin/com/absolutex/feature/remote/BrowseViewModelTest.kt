package com.absolutex.feature.remote

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.absolutex.remote.core.TransportCoverFetcher
import com.absolutex.remote.core.parseRemoteUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Browse ViewModel over a scripted in-memory folder tree: the listing seam is faked the
 * way [FakeRangeTransport]-style fakes fake the transport seam, while archive parsing
 * (covers decode real ZIPs through the real fetcher), Uri round-trips and the state
 * machine (loading/content/failure/retry/back/restore) are all real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    // viewModelScope runs on Dispatchers.Main: an eager test dispatcher keeps it synchronous.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun resetMain() {
        Dispatchers.resetMain()
    }

    private class FakeBrowser(
        var root: String = "/books",
        var tree: Map<String, List<RemoteEntry>> = emptyMap(),
        var failures: Int = 0,
    ) : RemoteBrowser {
        val listed = ArrayList<String>()

        override suspend fun rootPath(serverId: String): String = root

        override suspend fun listDir(serverId: String, path: String): List<RemoteEntry> {
            listed.add(path)
            if (failures > 0) {
                failures--
                throw IOException("server down")
            }
            return tree[path] ?: throw IOException("no such dir: $path")
        }
    }

    /** In-memory range transport so covers parse real archives, never canned pages. */
    private class FakeCoverTransport(val bytes: ByteArray) : com.absolutex.remote.core.RangeTransport {
        var closes = 0

        override fun sizeBytes(): Long = bytes.size.toLong()

        override fun readAt(offset: Long, length: Int): ByteArray {
            if (offset + length > bytes.size) throw IOException("read past end")
            return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        }

        override fun close() {
            closes++
        }
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun history(name: String = "browse.preferences_pb"): BrowseHistoryStore =
        BrowseHistoryStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job()),
                produceFile = { File(tmp.root, name) },
            ),
        )

    private fun viewModel(
        handle: SavedStateHandle,
        browser: FakeBrowser = FakeBrowser(),
        archives: Map<String, ByteArray> = emptyMap(),
        store: BrowseHistoryStore = history(),
    ): BrowseViewModel {
        val fetcher = TransportCoverFetcher { _, path ->
            FakeCoverTransport(archives[path] ?: throw IOException("no such book: $path"))
        }
        return BrowseViewModel(handle, browser, fetcher, store)
    }

    private fun handle(serverId: String = "srv-1", path: String? = null): SavedStateHandle =
        SavedStateHandle(
            buildMap {
                put("serverId", serverId)
                if (path != null) put("path", Uri.encode(path))
            },
        )

    private fun dir(path: String, vararg names: String): List<RemoteEntry> =
        names.map { name ->
            val isDir = !name.endsWith(".cbz")
            RemoteEntry(name, join(path, name), isDir, if (isDir) 0L else 128L)
        }

    /** Listings hop to IO, so every state assertion awaits instead of reading eagerly. */
    private suspend fun contentAt(vm: BrowseViewModel, path: String): BrowseState.Content =
        vm.state.first { it is BrowseState.Content && it.path == path } as BrowseState.Content

    @Test fun `starts at the root and lists content`() = runTest {
        val browser = FakeBrowser(tree = mapOf("/books" to dir("/books", "sub", "a.cbz")))
        val vm = viewModel(handle(), browser)
        val content = contentAt(vm, "/books")
        assertEquals("/books", content.rootPath)
        assertEquals(listOf("sub", "a.cbz"), content.entries.map { it.name })
        assertEquals(listOf("/books"), browser.listed)
    }

    @Test fun `failure shows the error and retry recovers`() = runTest {
        val browser = FakeBrowser(
            tree = mapOf("/books" to dir("/books", "a.cbz")),
            failures = 1,
        )
        val vm = viewModel(handle(), browser)
        val failed = vm.state.first { it is BrowseState.Failed } as BrowseState.Failed
        assertEquals("/books", failed.path)
        vm.retry()
        val content = contentAt(vm, "/books")
        assertEquals(listOf("a.cbz"), content.entries.map { it.name })
    }

    @Test fun `vanishing mid-listing never crashes and keeps its path`() = runTest {
        val browser = FakeBrowser(failures = Int.MAX_VALUE)
        val vm = viewModel(handle(), browser)
        val failed = vm.state.first { it is BrowseState.Failed } as BrowseState.Failed
        assertEquals("/books", failed.path)
    }

    @Test fun `descend and back climb the tree`() = runTest {
        val browser = FakeBrowser(
            tree = mapOf(
                "/books" to dir("/books", "sub"),
                "/books/sub" to dir("/books/sub", "deep", "a.cbz"),
                "/books/sub/deep" to dir("/books/sub/deep", "b.cbz"),
            ),
        )
        val vm = viewModel(handle(), browser)
        contentAt(vm, "/books")
        vm.openDir("/books/sub")
        contentAt(vm, "/books/sub")
        vm.openDir("/books/sub/deep")
        contentAt(vm, "/books/sub/deep")
        assertTrue(vm.goUp())
        contentAt(vm, "/books/sub")
        assertTrue(vm.goUp())
        contentAt(vm, "/books")
        // At the record root the host pops instead of climbing past it.
        assertEquals(false, vm.goUp())
        contentAt(vm, "/books")
    }

    @Test fun `openDir remembers the path for restore and history`() = runTest {
        val browser = FakeBrowser(
            tree = mapOf(
                "/books" to dir("/books", "sub"),
                "/books/sub" to dir("/books/sub", "a.cbz"),
            ),
        )
        val holder = handle()
        val store = history()
        val fetcher = TransportCoverFetcher { _, _ -> throw IOException("no covers here") }
        val vm = BrowseViewModel(holder, browser, fetcher, store)
        contentAt(vm, "/books")
        vm.openDir("/books/sub")
        contentAt(vm, "/books/sub")
        // Restore is synchronous: the encoded path is in the handle before listing lands.
        assertEquals(Uri.encode("/books/sub"), holder.get<String>("path"))
        // The navigation awaits the persist before listing, so reaching content means the
        // history write has landed — no poll, no race.
        assertEquals("/books/sub", store.lastDir("srv-1"))
    }

    @Test fun `process death restores the saved folder over history`() = runTest {
        val browser = FakeBrowser(
            tree = mapOf(
                "/books" to dir("/books", "sub", "other"),
                "/books/sub" to dir("/books/sub", "a.cbz"),
                "/books/other" to dir("/books/other", "b.cbz"),
            ),
        )
        val store = history()
        store.saveDir("srv-1", "/books/other")
        // The SavedStateHandle carries what openDir stored: the encoded in-progress folder.
        val vm = viewModel(handle(path = "/books/sub"), browser, store = store)
        val content = contentAt(vm, "/books/sub")
        assertEquals(listOf("a.cbz"), content.entries.map { it.name })
    }

    @Test fun `first launch falls back to history then the record root`() = runTest {
        val browser = FakeBrowser(
            tree = mapOf(
                "/books" to dir("/books", "a.cbz"),
                "/books/sub" to dir("/books/sub", "b.cbz"),
            ),
        )
        val store = history()
        store.saveDir("srv-1", "/books/sub")
        val remembered = viewModel(handle(), browser, store = store)
        assertEquals("/books/sub", contentAt(remembered, "/books/sub").path)
        // No handle and no history: the record root, which the fake serves as "/books".
        // A separate store file: one DataStore per file, so no two instances share one.
        val fresh = viewModel(handle(serverId = "srv-9"), browser, store = history("fresh.preferences_pb"))
        assertEquals("/books", contentAt(fresh, "/books").path)
    }

    @Test fun `history round-trips per server and rejects hostile paths`() = runTest {
        val store = history()
        store.saveDir("a", "/books/sub")
        assertEquals("/books/sub", store.lastDir("a"))
        assertNull(store.lastDir("b"))
        // A hostile write never lands, so the good value survives it.
        store.saveDir("a", "/../escape")
        store.saveDir("a", "relative/path")
        assertEquals("/books/sub", store.lastDir("a"))
    }

    @Test fun `covers resolve to real first-page bytes and failures never block the list`() = runTest {
        val page = ByteArray(1024) { it.toByte() }
        val archives = mapOf(
            "/books/good.cbz" to zipBytes(mapOf("cover.jpg" to page, "p02.jpg" to ByteArray(512))),
            "/books/broken.cbz" to "not a zip at all".toByteArray(),
        )
        val browser = FakeBrowser(
            tree = mapOf("/books" to dir("/books", "good.cbz", "broken.cbz")),
        )
        val vm = viewModel(handle(), browser, archives)
        val content = contentAt(vm, "/books")
        // Pass-through order is the fake's insertion order; sorting is the resolver's job,
        // pinned in RemoteListingTest rather than asserted through the seam fake.
        assertEquals(listOf("good.cbz", "broken.cbz"), content.entries.map { it.name })
        // The list is content while covers resolve on their own pass; a hostile book
        // degrades per tile instead of failing the folder.
        val resolved = vm.coversByPath.first { it.size == 2 }
        val good = resolved["/books/good.cbz"] as CoverState.Ready
        assertTrue(good.bytes.contentEquals(page))
        assertEquals(CoverState.Failed, resolved["/books/broken.cbz"])
    }

    @Test fun `reader uris round-trip hostile names exactly`() = runTest {
        val names = listOf(
            "Batman + Robin.cbz",
            "a%2Fb.cbz",
            "100% legit + (special).cbz",
            "漫畫01.cbz",
            "plain.cbz",
        )
        val vm = viewModel(handle(serverId = "srv-1"))
        for (name in names) {
            val entry = RemoteEntry(name, "/books/$name", false, 1L)
            val parsed = parseRemoteUri(vm.readerUriFor(entry))
            assertEquals("srv-1", parsed.serverId)
            assertEquals("/books/$name", parsed.path)
            assertEquals(name, parsed.displayName)
        }
        // Spot-check the encoding itself: a literal plus must arrive encoded, never raw.
        val uri = vm.readerUriFor(RemoteEntry("Batman + Robin.cbz", "/Batman + Robin.cbz", false, 1L))
        assertEquals("absolutex-remote://srv-1/Batman%20%2B%20Robin.cbz", uri)
    }

    @Test fun `browse route round-trips hostile paths as one argument`() {
        val paths = listOf(
            "/Batman + Robin.cbz",
            "/comics/a%2Fb.cbz",
            "/漫畫/01.cbz",
            "/plain.cbz",
        )
        for (path in paths) {
            val route = browseRoute("srv-1", path)
            if (!route.startsWith("remote/browse/srv-1?path=")) {
                fail("route lost its shape: $route")
            }
            assertEquals(path, Uri.decode(route.substringAfter("?path=")))
        }
        // No start folder rides no path argument at all.
        assertEquals("remote/browse/srv-1", browseRoute("srv-1"))
    }
}
