package com.absolutex.remote.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
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

/** Robolectric provides the real org.json on the JVM (see KomgaClientTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncServersTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String = "sync_servers.preferences_pb") = File(tmp.root, name)

    private fun open(file: File): Pair<SyncServers, Job> {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return SyncServers(store) to job
    }

    private fun komga(id: String = "s1") = SyncServer(
        id = id,
        kind = ServerKind.KOMGA,
        baseUrl = "https://nas:25600",
        username = "alice",
    )

    @Test fun `empty store lists nothing`() = runTest {
        val (servers, job) = open(file())
        assertTrue(servers.current().isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `save and remove round-trip, surviving reopen`() = runTest {
        val f = file()
        val (first, firstJob) = open(f)
        first.save(komga())
        first.save(komga("s2").copy(kind = ServerKind.KAVITA, baseUrl = "http://lan:5000", allowCleartext = true))
        assertEquals(2, first.current().size)
        first.remove("s1")
        assertEquals(listOf("s2"), first.current().map { it.id })
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        val kept = reopened.current()
        assertEquals(1, kept.size)
        assertEquals(ServerKind.KAVITA, kept[0].kind)
        assertTrue(kept[0].allowCleartext)
        job.cancelAndJoin()
    }

    @Test fun `base URL normalises its trailing slash`() = runTest {
        val (servers, job) = open(file())
        servers.save(komga().copy(baseUrl = "https://nas:25600/"))
        assertEquals("https://nas:25600", servers.current()[0].baseUrl)
        job.cancelAndJoin()
    }

    @Test fun `https passes, plain http needs the opt-in`() {
        assertNull(validateServerUrl("https://nas:25600", false))
        assertNull(validateServerUrl("http://192.168.1.5:5000", true))
        assertTrue(validateServerUrl("http://192.168.1.5:5000", false)?.contains("cleartext") == true)
        assertTrue(validateServerUrl("", false) != null)
        assertTrue(validateServerUrl("not a url", false) != null)
        assertTrue(validateServerUrl("ftp://nas/x", false) != null)
        assertTrue(validateServerUrl("https://", false) != null)
    }

    @Test fun `saving an invalid URL throws before touching the store`() = runTest {
        val (servers, job) = open(file())
        val result = runCatching { servers.save(komga().copy(baseUrl = "http://nas:25600")) }
        assertTrue(result.isFailure)
        assertTrue(servers.current().isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `one corrupt record is skipped, never fatal to its neighbours`() = runTest {
        val f = file()
        val (first, firstJob) = open(f)
        first.save(komga("good"))
        first.save(komga("alsogood").copy(kind = ServerKind.KAVITA))
        // Hand-corrupt the document: an unknown kind on the second record only. The store
        // file is binary protobuf, so the replacement keeps its length or nothing parses.
        // Hand-corrupt the document: an unknown kind on the second record only. The store
        // file is binary protobuf, so the replacement keeps its length or nothing parses.
        val raw = f.readBytes().toString(Charsets.ISO_8859_1)
        val corrupted = raw.replace("\"kind\":\"KAVITA\"", "\"kind\":\"KAVITB\"")
        f.writeBytes(corrupted.toByteArray(Charsets.ISO_8859_1))
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(listOf("good"), reopened.current().map { it.id })
        job.cancelAndJoin()
    }
}
