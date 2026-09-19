package com.absolutex.remote.sync

import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.remote.core.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Clock-skew and push-clamp behaviour through the real sync runners over a scripted
 * transport. Stamps stay relative to the wall clock (never fixed): skew is a relationship
 * between stamps, and fixed stamps would silently encode one particular skew. Robolectric
 * provides the real org.json on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSkewTest {

    private class FakeProgressDao : ProgressDao {
        val rows = mutableMapOf<String, ReadingProgress>()

        override suspend fun get(bookId: String): ReadingProgress? = rows[bookId]

        override suspend fun upsert(progress: ReadingProgress) {
            rows[progress.bookId] = progress
        }

        override fun observe(bookId: String): Flow<ReadingProgress?> = flowOf(rows[bookId])

        override suspend fun mostRecent(): ReadingProgress? = rows.values.maxByOrNull { it.updatedAt }

        override fun observeAll(): Flow<List<ReadingProgress>> = flowOf(rows.values.toList())
    }

    private val bookId = "Batman 001.cbz:2000"

    private fun daoWith(pageIndex: Int, updatedAt: Long): FakeProgressDao =
        FakeProgressDao().apply { rows[bookId] = ReadingProgress(bookId, pageIndex, 24, updatedAt) }

    private fun komgaSecrets(): SyncSecrets =
        SyncSecrets(InMemoryCredentialStore()).apply { savePassword("srv", "pw".toCharArray()) }

    private fun komgaServer() = SyncServer(
        id = "srv",
        kind = ServerKind.KOMGA,
        baseUrl = "http://lan:8080",
        allowCleartext = true,
        username = "u",
    )

    private fun komgaBook(progress: String?): String =
        "{\"id\":\"b1\",\"name\":\"Batman 001.cbz\",\"seriesId\":\"s1\"," +
            "\"media\":{\"pagesCount\":24},\"sizeBytes\":2000" +
            (if (progress == null) "" else ",\"readProgress\":$progress") + "}"

    private fun enqueueKomgaListing(fake: FakeHttpCall, serverDateMs: Long) {
        fake.enqueue(HttpResponse(200, "{\"content\":[{\"id\":\"s1\",\"name\":\"S\"}],\"last\":true}", serverDateMs))
        fake.enqueue(HttpResponse(200, "{\"content\":[${komgaBook(null)}],\"last\":true}", serverDateMs))
    }

    @Test fun `two-hour skew the other way does not pull a stale remote`() = runTest {
        // Mirror image: the server runs 2h ahead. True order: remote page 5 written 2h ago,
        // local page 10 written 10 min ago. Uncorrected, the inflated remote stamp wins and
        // the fresh page 10 rewinds to 5; corrected, the local page pushes instead.
        val now = System.currentTimeMillis()
        val serverDate = now + 7_200_000L
        val remoteWrite = now
        val localStamp = now - 600_000L
        val fake = FakeHttpCall()
        enqueueKomgaListing(fake, serverDateMs = serverDate)
        val progress = "{\"page\":5,\"completed\":false,\"lastModified\":\"${Instant.ofEpochMilli(remoteWrite)}\"}"
        fake.enqueue(HttpResponse(200, komgaBook(progress), serverDate))
        fake.enqueue(HttpResponse(204, "", serverDate))
        val dao = daoWith(pageIndex = 10, updatedAt = localStamp)
        val local = SyncProgress(bookId, 10, 24, localStamp)
        KomgaSync(fake, komgaSecrets(), dao, ServerClock()).sync(komgaServer(), local)
        val patch = JSONObject(fake.requests.single { it.method == "PATCH" }.body)
        assertEquals(11, patch.getInt("page"))
        assertEquals(10, dao.get(bookId)?.pageIndex)
    }

    @Test fun `two-hour skew does not push stale local over newer komga remote`() = runTest {
        // True order: remote page 20 written 10 min ago; local page 10 written 30 min ago on a
        // phone whose clock runs 2h fast. Uncorrected, the local stamp wins by 100 minutes and
        // the stale page pushes; corrected, the remote wins by 20 minutes. The skew is a
        // relationship: the dated responses read 2h behind the client's now, exactly as a
        // truthful server looks to a 2h-fast phone.
        val now = System.currentTimeMillis()
        val serverDate = now - 7_200_000L
        val remoteWrite = now - 600_000L
        val localStamp = now - 1_800_000L + 7_200_000L
        val fake = FakeHttpCall()
        enqueueKomgaListing(fake, serverDateMs = serverDate)
        val progress = "{\"page\":20,\"completed\":false,\"lastModified\":\"${Instant.ofEpochMilli(remoteWrite)}\"}"
        fake.enqueue(HttpResponse(200, komgaBook(progress), serverDate))
        val dao = daoWith(pageIndex = 9, updatedAt = localStamp)
        val local = SyncProgress(bookId, 9, 24, localStamp)
        KomgaSync(fake, komgaSecrets(), dao, ServerClock()).sync(komgaServer(), local)
        assertTrue("stale page pushed under skew", fake.requests.none { it.method == "PATCH" })
        val adopted = dao.get(bookId)!!
        assertEquals(19, adopted.pageIndex)
        assertTrue(kotlin.math.abs(adopted.updatedAt - (remoteWrite + 7_200_000L)) < 60_000L)
    }

    @Test fun `komga push clamps into the remote book`() = runTest {
        val now = System.currentTimeMillis()
        val fake = FakeHttpCall()
        enqueueKomgaListing(fake, serverDateMs = now)
        val progress = "{\"page\":5,\"completed\":false,\"lastModified\":\"${Instant.ofEpochMilli(now - 3_600_000L)}\"}"
        fake.enqueue(HttpResponse(200, komgaBook(progress), now))
        fake.enqueue(HttpResponse(204, "", now))
        // Local index far past the end (re-scanned file, replaced edition): the server must
        // receive its own last page, never a page that does not exist.
        val local = SyncProgress(bookId, 99, 24, now)
        KomgaSync(fake, komgaSecrets(), daoWith(99, now), ServerClock()).sync(komgaServer(), local)
        val patch = JSONObject(fake.requests.single { it.method == "PATCH" }.body)
        assertEquals(24, patch.getInt("page"))
    }

    private fun kavitaSecrets(): SyncSecrets =
        SyncSecrets(InMemoryCredentialStore()).apply { savePassword("srv", "s3cret".toCharArray()) }

    private fun kavitaServer() = SyncServer(
        id = "srv",
        kind = ServerKind.KAVITA,
        baseUrl = "http://lan:5000",
        allowCleartext = true,
        username = "alice",
    )

    private fun enqueueKavitaListing(fake: FakeHttpCall, serverDateMs: Long) {
        // Order follows KavitaLibrary.allChapterFiles: series page 0, its volumes, its volume,
        // then the terminating empty page — a misplaced empty page ends the walk early.
        fake.enqueue(HttpResponse(200, "{\"token\":\"t\",\"refreshToken\":\"r\"}", serverDateMs))
        fake.enqueue(HttpResponse(200, "[{\"id\":7,\"name\":\"S\"}]", serverDateMs))
        fake.enqueue(HttpResponse(200, "[{\"id\":3}]", serverDateMs))
        fake.enqueue(
            HttpResponse(
                200,
                "{\"id\":3,\"seriesId\":7,\"chapters\":[{\"id\":10,\"volumeId\":3,\"files\":[" +
                    "{\"id\":1,\"filePath\":\"/comics/Batman 001.cbz\",\"pages\":24,\"bytes\":2000}]}]}",
                serverDateMs,
            ),
        )
        fake.enqueue(HttpResponse(200, "[]", serverDateMs))
    }

    private fun kavitaProgressJson(pageNum: Int, stamp: String?): String =
        "{\"volumeId\":1,\"chapterId\":10,\"pageNum\":$pageNum,\"seriesId\":7,\"libraryId\":1" +
            (if (stamp == null) "" else ",\"lastModifiedUtc\":\"$stamp\"") + "}"

    @Test fun `two-hour skew does not push stale local over newer kavita remote`() = runTest {
        val now = System.currentTimeMillis()
        val serverDate = now - 7_200_000L
        val remoteWrite = now - 600_000L
        val localStamp = now - 1_800_000L + 7_200_000L
        val fake = FakeHttpCall()
        enqueueKavitaListing(fake, serverDateMs = serverDate)
        val remoteJson = kavitaProgressJson(19, Instant.ofEpochMilli(remoteWrite).toString())
        fake.enqueue(HttpResponse(200, remoteJson, serverDate))
        val dao = daoWith(pageIndex = 9, updatedAt = localStamp)
        val local = SyncProgress(bookId, 9, 24, localStamp)
        KavitaSync(fake, kavitaSecrets(), dao, ServerClock()).sync(kavitaServer(), local)
        assertTrue("stale page pushed under skew", fake.requests.none { it.url.endsWith("/api/Reader/progress") })
        val adopted = dao.get(bookId)!!
        assertEquals(19, adopted.pageIndex)
        assertTrue(kotlin.math.abs(adopted.updatedAt - (remoteWrite + 7_200_000L)) < 60_000L)
    }

    @Test fun `two-hour skew the other way does not pull a stale kavita remote`() = runTest {
        // Mirror image through the same decision path: the server runs 2h ahead, so the
        // stale remote stamp inflates past the fresh local one. Corrected, local pushes.
        val now = System.currentTimeMillis()
        val serverDate = now + 7_200_000L
        val remoteWrite = now
        val localStamp = now - 600_000L
        val fake = FakeHttpCall()
        enqueueKavitaListing(fake, serverDateMs = serverDate)
        val remoteJson = kavitaProgressJson(4, Instant.ofEpochMilli(remoteWrite).toString())
        fake.enqueue(HttpResponse(200, remoteJson, serverDate))
        fake.enqueue(HttpResponse(200, "{\"libraryId\":1}", serverDate))
        fake.enqueue(HttpResponse(200, "", serverDate))
        val dao = daoWith(pageIndex = 10, updatedAt = localStamp)
        val local = SyncProgress(bookId, 10, 24, localStamp)
        KavitaSync(fake, kavitaSecrets(), dao, ServerClock()).sync(kavitaServer(), local)
        val save = JSONObject(fake.requests.single { it.url.endsWith("/api/Reader/progress") }.body)
        assertEquals(10, save.getInt("pageNum"))
        assertEquals(10, dao.get(bookId)?.pageIndex)
    }

    @Test fun `kavita push clamps into the local book`() = runTest {
        val now = System.currentTimeMillis()
        val fake = FakeHttpCall()
        enqueueKavitaListing(fake, serverDateMs = now)
        fake.enqueue(HttpResponse(200, kavitaProgressJson(4, Instant.ofEpochMilli(now - 3_600_000L).toString()), now))
        fake.enqueue(HttpResponse(200, "{\"libraryId\":1}", now))
        fake.enqueue(HttpResponse(200, "", now))
        val local = SyncProgress(bookId, 99, 24, now)
        KavitaSync(fake, kavitaSecrets(), daoWith(99, now), ServerClock()).sync(kavitaServer(), local)
        val save = JSONObject(fake.requests.single { it.url.endsWith("/api/Reader/progress") }.body)
        assertEquals(23, save.getInt("pageNum"))
    }

    @Test fun `kavita absent progress stays missing under skew`() = runTest {
        // Page 0 at epoch 0 converts by identity: a large offset must not resurrect it into a
        // position that rewinds the book.
        val now = System.currentTimeMillis()
        val fake = FakeHttpCall()
        enqueueKavitaListing(fake, serverDateMs = now - 7_200_000L)
        fake.enqueue(HttpResponse(200, kavitaProgressJson(0, null), now - 7_200_000L))
        val dao = daoWith(pageIndex = 9, updatedAt = now)
        val pulled = KavitaSync(fake, kavitaSecrets(), dao, ServerClock())
            .pull(kavitaServer(), SyncProgress(bookId, 9, 24, now))
        assertNull(pulled)
        assertEquals(9, dao.get(bookId)?.pageIndex)
    }
}
