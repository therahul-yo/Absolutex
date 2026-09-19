package com.absolutex.remote.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.remote.core.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Controller triggers over a scripted transport: background push, late-pull offers, and the
 * browse/refresh entry points. Komga-only — the mechanics are server-agnostic and Komga's
 * three-call listing keeps the scripts readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTriggersTest {

    @get:Rule val tmp = TemporaryFolder()

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

    private lateinit var jobs: MutableList<Job>

    private fun dataStore(name: String) =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job().also { jobs += it }),
            produceFile = { File(tmp.root, name) },
        )

    private val bookA = "Batman 001.cbz:2000"
    private val bookB = "Saga 002.cbz:3000"

    private fun bookJson(id: String, name: String, size: Long, progress: String?): String =
        "{\"id\":\"$id\",\"name\":\"$name\",\"seriesId\":\"s1\"," +
            "\"media\":{\"pagesCount\":24},\"sizeBytes\":$size" +
            (if (progress == null) "" else ",\"readProgress\":$progress") + "}"

    private fun progressJson(page: Int, stamp: String): String =
        "{\"page\":$page,\"completed\":false,\"lastModified\":\"$stamp\"}"

    /** Series + books + one book GET; append a 204 for runs that push. */
    private fun enqueueBook(
        fake: FakeHttpCall,
        id: String,
        name: String,
        size: Long,
        remotePage: Int,
        remoteStamp: String,
        withPatchSlot: Boolean = false,
    ) {
        fake.enqueue(HttpResponse(200, "{\"content\":[{\"id\":\"s1\",\"name\":\"S\"}],\"last\":true}"))
        fake.enqueue(HttpResponse(200, "{\"content\":[${bookJson(id, name, size, null)}],\"last\":true}"))
        fake.enqueue(HttpResponse(200, bookJson(id, name, size, progressJson(remotePage, remoteStamp))))
        if (withPatchSlot) {
            fake.enqueue(HttpResponse(204, ""))
        }
    }

    private suspend fun controller(
        fake: FakeHttpCall,
        vararg serverIds: String = arrayOf("srv"),
    ): Pair<SyncController, FakeProgressDao> {
        jobs = mutableListOf()
        val dao = FakeProgressDao()
        val stores = RemoteServers(dataStore("servers.preferences_pb"))
        for (id in serverIds) {
            stores.save(
                KomgaServer(
                    id = id,
                    baseUrl = "http://$id:8080",
                    allowCleartext = true,
                    username = "u",
                ),
            )
        }
        val secrets = SyncSecrets(InMemoryCredentialStore())
        for (id in serverIds) {
            secrets.savePassword(id, "pw".toCharArray())
        }
        val queue = SyncQueue(dataStore("queue.preferences_pb"))
        return SyncController(dao, stores, secrets, queue, fake, ServerClock()) to dao
    }

    private fun local(bookId: String, pageIndex: Int, updatedAt: Long) =
        ReadingProgress(bookId, pageIndex, 24, updatedAt)

    @Test fun `background pushes the remembered open book without a close`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake)
        dao.upsert(local(bookA, 9, 1_800_000_000_000L))
        // Open first so the controller remembers the book; the call takes no id.
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 5, "2026-09-02T12:00:00Z")
        controller.onBookOpened(bookA)
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 5, "2026-09-02T12:00:00Z", withPatchSlot = true)
        controller.onAppBackgrounded()
        val patch = JSONObject(fake.requests.single { it.method == "PATCH" }.body)
        assertEquals(10, patch.getInt("page"))
    }

    @Test fun `late pull offers instead of moving the page`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake)
        // Local sits between the two remote writes: older than neither phase's fixture by
        // accident — the first pull finds nothing, the second finds a newer page.
        dao.upsert(local(bookA, 5, Instant.parse("2026-09-02T12:00:00Z").toEpochMilli()))
        // Open: remote older, nothing to adopt pre-first-paint.
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 3, "2026-09-01T12:00:00Z")
        assertNull(controller.onBookOpened(bookA))
        assertNull(controller.progressOffers.value)
        // Another device advances the book; a refresh lands it after the first page.
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 10, "2026-09-03T12:00:00Z")
        controller.onManualSync()
        val offer = controller.progressOffers.value
        assertEquals(RemoteProgressOffer(bookA, 9, "srv", "http://srv:8080"), offer)
        assertEquals(5, dao.get(bookA)?.pageIndex)
        controller.consumeOffer()
        assertNull(controller.progressOffers.value)
    }

    @Test fun `close clears the open state and the offer`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake)
        // Local older than the remote the refresh will land: close adopts, then clears.
        dao.upsert(local(bookA, 5, Instant.parse("2026-09-02T12:00:00Z").toEpochMilli()))
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 3, "2026-09-01T12:00:00Z")
        assertNull(controller.onBookOpened(bookA))
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 10, "2026-09-03T12:00:00Z")
        controller.onManualSync()
        assertTrue(controller.progressOffers.value != null)
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 10, "2026-09-03T12:00:00Z")
        controller.onBookClosed(bookA)
        assertNull(controller.progressOffers.value)
    }

    @Test fun `syncNow scopes to one server`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake, "srv-a", "srv-b")
        dao.upsert(local(bookA, 9, 1_800_000_000_000L))
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 5, "2026-09-02T12:00:00Z")
        controller.syncNow("srv-a")
        assertTrue(fake.requests.isNotEmpty())
        assertTrue(fake.requests.all { it.url.startsWith("http://srv-a:8080") })
    }

    @Test fun `syncNow on an unknown server is a no-op`() = runTest {
        val fake = FakeHttpCall()
        val (controller, _) = controller(fake)
        controller.syncNow("nope")
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun `syncBooks pushes only the listed books`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake)
        dao.upsert(local(bookA, 9, 1_800_000_000_000L))
        dao.upsert(local(bookB, 7, 1_800_000_000_000L))
        repeat(2) {
            enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 5, "2026-09-02T12:00:00Z", withPatchSlot = true)
        }
        controller.syncBooks(listOf(bookA))
        assertEquals(1, fake.requests.count { it.method == "PATCH" })
        assertTrue(fake.requests.none { it.url.contains("/books/b2") })
    }

    @Test fun `adopted pull carries the remote stamp so the next compare ties`() = runTest {
        val fake = FakeHttpCall()
        val (controller, dao) = controller(fake)
        dao.upsert(local(bookA, 2, 1_000_000_000_000L))
        val stamp = "2026-09-02T12:00:00Z"
        enqueueBook(fake, "b1", "Batman 001.cbz", 2000, 6, stamp)
        controller.onManualSync()
        val adopted = dao.get(bookA)!!
        assertEquals(5, adopted.pageIndex)
        assertEquals(Instant.parse(stamp).toEpochMilli(), adopted.updatedAt)
    }
}
