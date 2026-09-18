package com.absolutex.feature.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.HttpCall
import com.absolutex.remote.sync.HttpResponse
import com.absolutex.remote.sync.HttpBytesResponse
import com.absolutex.remote.sync.KavitaConnectionProbe
import com.absolutex.remote.sync.KomgaConnectionProbe
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.RemoteKind
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.ServerAdmin
import com.absolutex.remote.sync.ServerClock
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.SyncController
import com.absolutex.remote.sync.SyncQueue
import com.absolutex.remote.sync.SyncSecrets
import com.absolutex.remote.sync.InMemoryCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Form and list ViewModels over real stores (DataStore + secrets) with a scripted HTTP
 * transport. Robolectric stands up org.json and resources on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerViewModelTest {

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

    private class FakeProgressDao : ProgressDao {
        val rows = mutableMapOf<String, ReadingProgress>()
        private val feed = MutableStateFlow<List<ReadingProgress>>(emptyList())

        override suspend fun get(bookId: String): ReadingProgress? = rows[bookId]

        override suspend fun upsert(progress: ReadingProgress) {
            rows[progress.bookId] = progress
            feed.value = rows.values.toList()
        }

        override fun observe(bookId: String): Flow<ReadingProgress?> =
            feed.map { list -> list.firstOrNull { it.bookId == bookId } }

        override suspend fun mostRecent(): ReadingProgress? = rows.values.maxByOrNull { it.updatedAt }

        override fun observeAll(): Flow<List<ReadingProgress>> = feed
    }

    private class FakeHttp : HttpCall {
        // Synchronized: the sync under test appends on Dispatchers.IO while the test polls.
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var loginBody = "{\"token\":\"t\",\"refreshToken\":\"r\"}"
        var seriesBody = "{\"content\":[{\"id\":\"s1\",\"name\":\"S\"}],\"last\":true}"

        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            calls += "$method $url"
            return when {
                url.endsWith("/api/Account/login") -> HttpResponse(200, loginBody)
                url.contains("/api/v1/series/list") -> HttpResponse(200, seriesBody)
                url.contains("/api/v1/books/list") -> HttpResponse(
                    200,
                    "{\"content\":[{\"id\":\"b1\",\"name\":\"B.cbz\",\"seriesId\":\"s1\"," +
                        "\"media\":{\"pagesCount\":24},\"sizeBytes\":100}],\"last\":true}",
                )
                url.endsWith("/api/v1/books/b1") -> HttpResponse(
                    200,
                    "{\"id\":\"b1\",\"name\":\"B.cbz\",\"seriesId\":\"s1\",\"media\":{\"pagesCount\":24}," +
                        "\"sizeBytes\":100,\"readProgress\":{\"page\":1,\"completed\":false," +
                        "\"lastModified\":\"2026-09-01T12:00:00Z\"}}",
                )
                url.endsWith("/api/Reader/progress") -> HttpResponse(200, "")
                url.endsWith("/api/Library/libraries") -> HttpResponse(200, "[{\"id\":1,\"name\":\"L\"}]")
                method == "PATCH" -> HttpResponse(204, "")
                else -> HttpResponse(404, "")
            }
        }

        override fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse =
            HttpBytesResponse(404, ByteArray(0))
    }

    private fun dataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File(tmp.root, name) },
    )

    private fun stores() = RemoteServers(dataStore("remote.preferences_pb"))

    private fun secrets() = SyncSecrets(InMemoryCredentialStore())

    private fun listViewModel(
        servers: RemoteServers = stores(),
        http: FakeHttp = FakeHttp(),
        dao: FakeProgressDao = FakeProgressDao(),
        secrets: SyncSecrets = secrets(),
    ): ServerListViewModel {
        val queue = SyncQueue(dataStore("queue.preferences_pb"))
        val controller = SyncController(dao, servers, secrets, queue, http, ServerClock())
        return ServerListViewModel(servers, ServerAdmin(servers, secrets), controller)
    }

    private fun formViewModel(
        servers: RemoteServers = stores(),
        http: FakeHttp = FakeHttp(),
        serverId: String? = null,
    ): ServerFormViewModel = ServerFormViewModel(
        SavedStateHandle(if (serverId == null) emptyMap() else mapOf("serverId" to serverId)),
        servers,
        secrets(),
        KomgaConnectionProbe(http),
        KavitaConnectionProbe(http),
        FtpConnectionProbe(),
    )

    @Test fun `empty store shows the empty state`() = runTest {
        val viewModel = listViewModel()
        val state = viewModel.state.first { it is ServerListState.Ready }
        assertEquals(ServerListState.Ready(emptyList()), state)
    }

    @Test fun `saved servers list with labels`() = runTest {
        val servers = stores()
        servers.save(SmbServer("a", "nas", "comics", "books", username = "u"))
        val viewModel = listViewModel(servers)
        val state = viewModel.state.first { it is ServerListState.Ready } as ServerListState.Ready
        assertEquals(listOf("a"), state.servers.map { it.id })
    }

    @Test fun `delete wipes the record and its secrets`() = runTest {
        val servers = stores()
        val store = secrets()
        servers.save(SmbServer("a", "nas", "comics", "books", username = "u"))
        store.saveSmbPassword("a", "pw".toCharArray())
        val viewModel = listViewModel(servers, secrets = store)
        viewModel.deleteServer("a")
        viewModel.state.first {
            it is ServerListState.Ready && it.servers.isEmpty()
        }
        // The list updates on the record write; the secret wipe lands right after — poll.
        val deadline = System.currentTimeMillis() + 10_000L
        while (store.loadSmbPassword("a") != null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertNull(store.loadSmbPassword("a"))
    }

    @Test fun `refresh runs a sync pass and settles`() = runTest {
        val servers = stores()
        val http = FakeHttp()
        val dao = FakeProgressDao()
        val store = secrets()
        servers.save(KomgaServer("k", "http://k:8080", allowCleartext = true, username = "u"))
        // Basic auth loads the password per call: without it the pull fails before networking.
        store.savePassword("k", "pw".toCharArray())
        dao.upsert(ReadingProgress("B.cbz:100", 3, 24, 1_800_000_000_000L))
        val viewModel = listViewModel(servers, http, dao, store)
        viewModel.refresh()
        // A manual sync pulls through the server: the refresh drove network, then settled.
        val deadline = System.currentTimeMillis() + 10_000L
        fun listed(): Boolean = synchronized(http.calls) {
            http.calls.any { it.contains("/api/v1/books/list") }
        }
        while (!listed() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertTrue(listed())
        viewModel.refreshing.first { !it }
    }

    @Test fun `blank smb form blocks save with field errors`() = runTest {
        val servers = stores()
        val viewModel = formViewModel(servers)
        viewModel.update(ServerForm(kind = RemoteKind.SMB))
        viewModel.save()
        val status = viewModel.status.first { it.saveBlocked }
        assertTrue(status.invalidFields.contains(ServerFormViewModel.FIELD_HOST))
        assertTrue(status.invalidFields.contains(ServerFormViewModel.FIELD_SHARE))
        assertTrue(status.invalidFields.contains(ServerFormViewModel.FIELD_PASSWORD))
        assertTrue(servers.current().isEmpty())
    }

    @Test fun `valid smb form saves the record and the secret`() = runTest {
        val servers = stores()
        val store = secrets()
        val viewModel = ServerFormViewModel(
            SavedStateHandle(emptyMap()),
            servers,
            store,
            KomgaConnectionProbe(FakeHttp()),
            KavitaConnectionProbe(FakeHttp()),
            FtpConnectionProbe(),
        )
        viewModel.update(
            ServerForm(
                kind = RemoteKind.SMB, host = "nas", share = "comics", path = "books",
                port = "445", username = "u", password = "pw",
            ),
        )
        viewModel.save()
        viewModel.status.first { it.saved }
        val saved = servers.current().single() as SmbServer
        assertEquals("nas", saved.host)
        assertTrue(store.loadSmbPassword(saved.id)?.contentEquals("pw".toCharArray()) == true)
    }

    @Test fun `komga live test reports ok on the current values`() = runTest {
        val viewModel = formViewModel()
        viewModel.update(
            ServerForm(kind = RemoteKind.KOMGA, baseUrl = "https://k:8080", username = "u", password = "pw"),
        )
        viewModel.testConnection()
        val result = viewModel.status.first { it.testResult != null }.testResult
        assertEquals(ConnectionResult.Ok, result)
    }

    @Test fun `smb has no live test yet`() = runTest {
        val viewModel = formViewModel()
        viewModel.update(
            ServerForm(
                kind = RemoteKind.SMB, host = "nas", share = "comics", path = "books",
                port = "445", username = "u", password = "pw",
            ),
        )
        viewModel.testConnection()
        val status = viewModel.status.first { !it.testing }
        assertNull(status.testResult)
    }

    @Test fun `edit prefills the record`() = runTest {
        val servers = stores()
        servers.save(KomgaServer("k", "https://komga.lan", username = "u"))
        val viewModel = formViewModel(servers, serverId = "k")
        val form = viewModel.form.first { it.serverId == "k" }
        assertEquals(RemoteKind.KOMGA, form.kind)
        assertEquals("https://komga.lan", form.baseUrl)
        assertEquals("u", form.username)
    }

    @Test fun `changing kind while editing blocks save without leaking secrets`() = runTest {
        val servers = stores()
        val store = secrets()
        servers.save(SmbServer("x", "nas", "comics", "books", username = "u"))
        store.saveSmbPassword("x", "nas-pw".toCharArray())
        val viewModel = ServerFormViewModel(
            SavedStateHandle(mapOf("serverId" to "x")),
            servers,
            store,
            KomgaConnectionProbe(FakeHttp()),
            KavitaConnectionProbe(FakeHttp()),
            FtpConnectionProbe(),
        )
        viewModel.form.first { it.serverId == "x" }
        // Switch SMB -> Komga with the prefilled NAS password still in the field.
        viewModel.update(
            ServerForm(
                serverId = "x",
                kind = RemoteKind.KOMGA,
                baseUrl = "https://k:8080",
                username = "u",
                password = "nas-pw",
            ),
        )
        viewModel.save()
        val status = viewModel.status.first { it.saveBlocked }
        assertTrue(status.invalidFields.contains(ServerFormViewModel.FIELD_KIND))
        // Record untouched, and the NAS password never reached the Komga slot.
        assertTrue(servers.current().single() is SmbServer)
        assertNull(store.loadApiKey("x"))
        assertTrue(store.loadSmbPassword("x")?.contentEquals("nas-pw".toCharArray()) == true)
        // The live test is gated the same way: nothing is sent anywhere.
        viewModel.testConnection()
        val tested = viewModel.status.first {
            it.saveBlocked && it.invalidFields.contains(ServerFormViewModel.FIELD_KIND)
        }
        assertNull(tested.testResult)
    }

    @Test fun `ftp mapping covers the result variants`() {
        assertEquals(ConnectionResult.AuthFailed, mapFtpFailure(IOException("FTP login refused: x")))
        assertEquals(ConnectionResult.NotFound, mapFtpFailure(IOException("cannot list FTP path: x")))
        assertEquals(ConnectionResult.Unreachable, mapFtpFailure(IOException("reset")))
        assertEquals(
            ConnectionResult.SecurityRefused,
            mapFtpFailure(IOException("tls", javax.net.ssl.SSLHandshakeException("hs"))),
        )
    }
}
