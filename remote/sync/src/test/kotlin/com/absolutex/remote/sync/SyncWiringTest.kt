package com.absolutex.remote.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
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
import java.time.Instant

private fun respond(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()

/**
 * Sync wiring against a real in-process HTTP server speaking the actual Komga and Kavita
 * endpoints the clients call — not a fake client. A fake HttpCall cannot reproduce a genuine
 * 401/5xx, a 404-with-body, or a JSON shape the parser must survive, which is exactly what
 * last-write-wins misbehaves on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWiringTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeProgressDao
    private lateinit var jobs: MutableList<Job>

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

    private fun dataStore(
        name: String,
    ): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val job = Job()
        jobs += job
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(tmp.root, name) },
        )
    }

    private fun local(
        bookId: String = "Batman 001.cbz:2000",
        pageIndex: Int = 5,
        pageCount: Int = 24,
        updatedAt: Long = 1_700_000_000_000L,
    ) = ReadingProgress(bookId, pageIndex, pageCount, updatedAt)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakeProgressDao()
        jobs = mutableListOf()
    }

    @After fun tearDown() {
        server.close()
    }

    private suspend fun komgaController(
        configure: FakeKomga.() -> Unit = {},
        http: HttpCall = HttpUrlConnectionCall(),
    ): Triple<SyncController, FakeKomga, SyncQueue> {
        val fake = FakeKomga().apply(configure)
        server.dispatcher = fake
        val base = server.url("/").toString().trimEnd('/')
        val stores = RemoteServers(dataStore("servers.preferences_pb"))
        stores.save(
            KomgaServer(id = "srv", baseUrl = base, allowCleartext = true, usesApiKey = true),
        )
        val secrets = SyncSecrets(InMemoryCredentialStore())
        secrets.saveApiKey("srv", "key".toCharArray())
        val queue = SyncQueue(dataStore("queue.preferences_pb"))
        val controller = SyncController(dao, stores, secrets, queue, http, ServerClock())
        return Triple(controller, fake, queue)
    }

    /**
     * JDK HTTP transport for tests that must actually send PATCH: the platform
     * HttpURLConnection rejects PATCH on the JVM (ProtocolException) while Android's
     * OkHttp-backed implementation allows it. So the MockWebServer half of push coverage
     * runs here and the URL/body half runs in KomgaClientTest's fakes; production stays on
     * HttpURLConnection and the device checklist covers PATCH on device.
     */
    private class JdkHttpCall : HttpCall {
        private val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()

        private fun java.net.http.HttpResponse<*>.serverDate(): Long? {
            val raw = headers().firstValue(DATE_HEADER).orElse(null) ?: return null
            return runCatching {
                java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
            }.getOrNull()?.takeIf { it > 0L }
        }

        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            headers.forEach { (name, value) -> builder.header(name, value) }
            val publisher = if (body != null) {
                java.net.http.HttpRequest.BodyPublishers.ofString(body)
            } else {
                java.net.http.HttpRequest.BodyPublishers.noBody()
            }
            builder.method(method, publisher)
            val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            return HttpResponse(response.statusCode(), response.body(), response.serverDate())
        }

        override fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse {
            val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            headers.forEach { (name, value) -> builder.header(name, value) }
            builder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
            val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
            return HttpBytesResponse(response.statusCode(), response.body())
        }
    }

    /** Canned Komga with per-test behaviour switches. Records every PATCH body. */
    inner class FakeKomga : Dispatcher() {
        var patchCode = 204
        var remotePage = 6
        var remoteUpdatedAt = "2026-09-02T12:00:00Z"
        var remoteAbsent = false
        var duplicate = false
        var calls = 0
        val patches = mutableListOf<JSONObject>()

        private fun book(id: String, name: String, size: Long, progress: String?): String =
            "{\"id\":\"$id\",\"name\":\"$name\",\"seriesId\":\"s1\"," +
                "\"media\":{\"pagesCount\":24},\"sizeBytes\":$size" +
                (if (progress == null) "" else ",\"readProgress\":$progress") + "}"

        private fun progressJson(): String =
            "{\"page\":$remotePage,\"completed\":false,\"created\":\"2026-09-01T09:00:00Z\"," +
                "\"readDate\":\"2026-09-01T09:00:00Z\",\"lastModified\":\"$remoteUpdatedAt\"," +
                "\"deviceId\":\"d\",\"deviceName\":\"n\"}"

        override fun dispatch(request: RecordedRequest): MockResponse {
            calls++
            val path = request.target.orEmpty()
            return when {
                path.startsWith("/api/v1/series/list") ->
                    respond(200, "{\"content\":[{\"id\":\"s1\",\"name\":\"S\"}],\"last\":true}")
                path.startsWith("/api/v1/books/list") -> {
                    val books = if (duplicate) {
                        book("b1", "Batman 001.cbz", 2000, null) + "," +
                            book("b2", "Batman 001.cbz", 2000, null)
                    } else {
                        book("b1", "Batman 001.cbz", 2000, null)
                    }
                    respond(200, "{\"content\":[$books],\"last\":true}")
                }
                path == "/api/v1/books/b1" ->
                    if (remoteAbsent) {
                        respond(404)
                    } else {
                        respond(200, book("b1", "Batman 001.cbz", 2000, progressJson()))
                    }
                path == "/api/v1/books/b1/read-progress" && request.method == "PATCH" -> {
                    patches += JSONObject(request.body?.utf8().orEmpty())
                    respond(patchCode)
                }
                else -> respond(404)
            }
        }
    }

    @Test fun `push round-trips when local is newer`() = runTest {
        val (controller, fake, _) = komgaController(http = JdkHttpCall())
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.patches.size)
        // Local pageIndex 5 → Komga one-based page 6.
        assertEquals(6, fake.patches[0].getInt("page"))
    }

    @Test fun `pull adopts newer remote before first paint`() = runTest {
        val (controller, _, _) = komgaController(configure = {
            remotePage = 10
            remoteUpdatedAt = "2026-09-03T12:00:00Z"
        })
        dao.upsert(local(pageIndex = 5, updatedAt = 1_700_000_000_000L))
        val pulled = controller.onBookOpened("Batman 001.cbz:2000")
        // Remote one-based 10 → local index 9.
        assertEquals(9, pulled?.pageIndex)
    }

    @Test fun `last-write-wins holds both ways`() = runTest {
        val (controller, _, _) = komgaController()
        // Local newer: pushes, database untouched by remote.
        dao.upsert(local(pageIndex = 5, updatedAt = 1_800_000_000_000L))
        controller.onManualSync()
        assertEquals(5, dao.get("Batman 001.cbz:2000")?.pageIndex)
        // Remote newer: adopts with the remote timestamp, so the next compare ties.
        dao.upsert(local(pageIndex = 2, updatedAt = 1_000_000_000_000L))
        controller.onManualSync()
        val adopted = dao.get("Batman 001.cbz:2000")!!
        assertEquals(5, adopted.pageIndex)
        assertEquals(Instant.parse("2026-09-02T12:00:00Z").toEpochMilli(), adopted.updatedAt)
    }

    @Test fun `unmatched book is left alone`() = runTest {
        val (controller, fake, _) = komgaController()
        dao.upsert(local(bookId = "Unknown.cbz:999", updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Unknown.cbz:999")
        // The listing ran (calls happened) but nothing was pushed and nothing adopted.
        assertTrue(fake.calls > 0)
        assertTrue(fake.patches.isEmpty())
        assertEquals(5, dao.get("Unknown.cbz:999")?.pageIndex)
    }

    @Test fun `duplicate match is left alone, never guessed`() = runTest {
        val (controller, fake, _) = komgaController(configure = { duplicate = true })
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertTrue(fake.patches.isEmpty())
    }

    @Test fun `a 401 stops the server without queueing`() = runTest {
        val (controller, fake, queue) = komgaController({ patchCode = 401 }, JdkHttpCall())
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        // Retried auth is an account lockout: no queue entry, and the server is marked.
        assertTrue(queue.due(Long.MAX_VALUE).isEmpty())
        assertEquals(setOf("srv"), controller.stoppedServers.value)
        // A second trigger makes no new requests at all.
        val calls = fake.calls
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(calls, fake.calls)
    }

    @Test fun `a stopped server recovers on success`() = runTest {
        val (controller, fake, _) = komgaController({ patchCode = 401 }, JdkHttpCall())
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(setOf("srv"), controller.stoppedServers.value)
        // The server heals: the next explicit pass attempts it (success unstops), and the
        // following close pushes again. (The fake records the refused attempt too.)
        fake.patchCode = 204
        controller.onManualSync()
        assertTrue(controller.stoppedServers.value.isEmpty())
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(2, fake.patches.size)
    }

    @Test fun `a 404 on a mapped book invalidates without pushing or queueing`() = runTest {
        // The listing matched, but the book itself is gone server-side: pushing into the
        // void would loop, so the mapping is dropped silently instead.
        val (controller, fake, queue) = komgaController({ remoteAbsent = true }, JdkHttpCall())
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertTrue(fake.patches.isEmpty())
        assertTrue(queue.due(Long.MAX_VALUE).isEmpty())
    }

    @Test fun `a 500 queues the push for retry`() = runTest {
        val (controller, _, queue) = komgaController({ patchCode = 500 }, JdkHttpCall())
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertTrue(queue.due(Long.MAX_VALUE).isNotEmpty())
    }

    private suspend fun kavitaController(
        apiKey: Boolean = false,
        configure: FakeKavita.() -> Unit = {},
    ): Triple<SyncController, FakeKavita, SyncQueue> {
        val fake = FakeKavita().apply(configure)
        server.dispatcher = fake
        val base = server.url("/").toString().trimEnd('/')
        val stores = RemoteServers(dataStore("servers.preferences_pb"))
        stores.save(
            KavitaServer(
                id = "srv",
                baseUrl = base,
                allowCleartext = true,
                username = if (apiKey) null else "alice",
                usesApiKey = apiKey,
            ),
        )
        val secrets = SyncSecrets(InMemoryCredentialStore())
        if (apiKey) {
            secrets.saveApiKey("srv", "key".toCharArray())
        } else {
            secrets.savePassword("srv", "s3cret".toCharArray())
        }
        val queue = SyncQueue(dataStore("queue.preferences_pb"))
        val controller = SyncController(dao, stores, secrets, queue, HttpUrlConnectionCall(), ServerClock())
        return Triple(controller, fake, queue)
    }

    /** Canned Kavita: login, libraries, series, volumes with files, progress. */
    inner class FakeKavita : Dispatcher() {
        var saveCode = 200
        var remotePageNum = 8
        var remoteStamp: String? = "2026-09-02T12:00:00Z"
        var progressGone = false
        var remotePages = 24
        var exchanges = 0
        var refreshCalls = 0
        var expireSeriesOnce = false
        private var seriesCalls = 0
        val saves = mutableListOf<JSONObject>()

        private fun seriesPage(path: String): MockResponse {
            // PageNumber comes in the query: page 0 lists, later pages end the walk. Keyed
            // on the request (not a call counter) so repeated sync triggers re-list cleanly.
            val first = "PageNumber=0" in path
            if (expireSeriesOnce && seriesCalls++ == 0) {
                return respond(401)
            }
            return if (first) {
                respond(200, "[{\"id\":7,\"name\":\"S\"}]")
            } else {
                respond(200, "[]")
            }
        }

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                return when {
                    path == "/api/Account/login" ->
                        respond(200, "{\"token\":\"t\",\"refreshToken\":\"r\"}")
                    path.startsWith("/api/Plugin/authenticate") -> {
                        exchanges++
                        respond(200, "{\"token\":\"jwt-k\",\"refreshToken\":\"r-k\"}")
                    }
                    path == "/api/Account/refresh-token" -> {
                        refreshCalls++
                        respond(200, "{\"token\":\"jwt-2\",\"refreshToken\":\"r-2\"}")
                    }
                    path == "/api/Library/libraries" ->
                        respond(200, "[{\"id\":1,\"name\":\"L\"}]")
                    path.startsWith("/api/Series/v2") -> seriesPage(path)
                    path.startsWith("/api/Series/volumes") ->
                        respond(200, "[{\"id\":3}]")
                    path.startsWith("/api/Series/volume") ->
                        respond(200,
                            "{\"id\":3,\"seriesId\":7,\"chapters\":[{" +
                                "\"id\":10,\"volumeId\":3,\"files\":[" +
                                "{\"id\":1,\"filePath\":\"/comics/Batman 001.cbz\"," +
                                "\"pages\":$remotePages,\"bytes\":2000}" +
                                "]}]}",
                        )
                    path.startsWith("/api/Reader/get-progress") ->
                        if (progressGone) {
                            respond(404)
                        } else if (remoteStamp == null) {
                            // The server's real absent shape: 200 with pageNum 0 and no
                            // stamp — adopting that would rewind, so it reads as missing.
                            respond(200,
                                "{\"volumeId\":1,\"chapterId\":10,\"pageNum\":0," +
                                    "\"seriesId\":7,\"libraryId\":1}",
                            )
                        } else {
                            respond(200,
                                "{\"volumeId\":1,\"chapterId\":10,\"pageNum\":$remotePageNum," +
                                    "\"seriesId\":7,\"libraryId\":1,\"lastModifiedUtc\":\"$remoteStamp\"}",
                            )
                        }
                path == "/api/Reader/progress" -> {
                    saves += JSONObject(request.body?.utf8().orEmpty())
                    respond(saveCode)
                }
                path.startsWith("/api/Series/7") ->
                    respond(200, "{\"libraryId\":1}")
                else -> respond(404)
            }
        }
    }

    @Test fun `kavita push sends the zero-based index`() = runTest {
        val (controller, fake, _) = kavitaController()
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.saves.size)
        assertEquals(5, fake.saves[0].getInt("pageNum"))
        assertEquals(10, fake.saves[0].getInt("chapterId"))
    }

    @Test fun `kavita pull adopts a newer page number`() = runTest {
        val (controller, _, _) = kavitaController(configure = {
            remotePageNum = 12
            remoteStamp = "2026-09-03T12:00:00Z"
        })
        dao.upsert(local(pageIndex = 5, updatedAt = 1_700_000_000_000L))
        val pulled = controller.onBookOpened("Batman 001.cbz:2000")
        assertEquals(12, pulled?.pageIndex)
    }

    @Test fun `kavita absent progress reads as missing, never as page one`() = runTest {
        // The server's no-progress default (pageNum 0, no stamp): pushing local is correct,
        // adopting 0 over a real position would rewind the book.
        val (controller, fake, _) = kavitaController { remoteStamp = null }
        dao.upsert(local(pageIndex = 5, updatedAt = 1_800_000_000_000L))
        assertNull(controller.onBookOpened("Batman 001.cbz:2000"))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.saves.size)
    }

    @Test fun `kavita 404 on a mapped chapter invalidates`() = runTest {
        val (controller, fake, queue) = kavitaController { progressGone = true }
        dao.upsert(local(pageIndex = 5, updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertTrue(fake.saves.isEmpty())
        assertTrue(queue.due(Long.MAX_VALUE).isEmpty())
    }

    @Test fun `kavita push clamps into the remote chapter count`() = runTest {
        // Remote chapter has 20 pages, local file 24: finishing locally at index 23 must
        // push the remote last page (19), never a page number past the chapter.
        val (controller, fake, _) = kavitaController { remotePages = 20 }
        dao.upsert(local(pageIndex = 23, updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.saves.size)
        assertEquals(19, fake.saves[0].getInt("pageNum"))
    }

    @Test fun `kavita api key exchanges once across syncs`() = runTest {
        val (controller, fake, _) = kavitaController(apiKey = true)
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.exchanges)
        assertEquals(2, fake.saves.size)
    }

    @Test fun `kavita expired cached token refreshes without re-exchange`() = runTest {
        val (controller, fake, _) = kavitaController(apiKey = true) { expireSeriesOnce = true }
        dao.upsert(local(updatedAt = 1_800_000_000_000L))
        controller.onBookClosed("Batman 001.cbz:2000")
        assertEquals(1, fake.exchanges)
        assertEquals(1, fake.refreshCalls)
        assertEquals(1, fake.saves.size)
    }
}
