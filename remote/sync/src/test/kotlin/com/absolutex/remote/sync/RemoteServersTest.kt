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
class RemoteServersTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String = "remote_servers.preferences_pb") = File(tmp.root, name)

    private fun open(file: File): Pair<RemoteServers, Job> {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return RemoteServers(store) to job
    }

    @Test fun `empty store lists nothing`() = runTest {
        val (servers, job) = open(file())
        assertTrue(servers.current().isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `save remove and reopen round-trip across all four kinds`() = runTest {
        val f = file()
        val (first, firstJob) = open(f)
        first.save(SmbServer(id = "smb", host = "nas", share = "media", path = "comics", username = "a"))
        first.save(FtpServer(id = "ftp", host = "nas", path = "/pub", username = "b", useTls = true))
        first.save(KomgaServer(id = "komga", baseUrl = "https://nas:25600", username = "alice"))
        val kavita = KavitaServer(
            id = "kavita",
            baseUrl = "http://192.168.1.5:5000",
            allowCleartext = true,
            usesApiKey = true,
        )
        first.save(kavita)
        assertEquals(4, first.current().size)
        // Re-saving an id replaces it; like SyncServers, the record moves to the end.
        val moved = SmbServer(id = "smb", host = "nas2", share = "media", path = "comics", username = "a")
        first.save(moved)
        assertEquals("nas2", (first.current().first { it.id == "smb" } as SmbServer).host)
        first.remove("ftp")
        assertEquals(listOf("komga", "kavita", "smb"), first.current().map { it.id })
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        val kept = reopened.current()
        assertEquals(listOf("komga", "kavita", "smb"), kept.map { it.id })
        assertEquals(RemoteKind.SMB, kept[2].kind)
        assertEquals("media", (kept[2] as SmbServer).share)
        assertEquals("alice", (kept[0] as KomgaServer).username)
        assertTrue((kept[1] as KavitaServer).allowCleartext)
        job.cancelAndJoin()
    }

    @Test fun `trailing slashes normalise on urls and paths`() = runTest {
        val (servers, job) = open(file())
        servers.save(KomgaServer(id = "k", baseUrl = "https://nas:25600/"))
        val smb = SmbServer(id = "s", host = "nas", share = "media", path = "comics/", username = "a")
        servers.save(smb)
        servers.save(FtpServer(id = "f", host = "nas", path = "/pub/", username = "b", useTls = true))
        val root = SmbServer(id = "root", host = "nas", share = "media", path = "/", username = "a")
        servers.save(root)
        val byId = servers.current().associateBy { it.id }
        assertEquals("https://nas:25600", (byId["k"] as KomgaServer).baseUrl)
        assertEquals("comics", (byId["s"] as SmbServer).path)
        assertEquals("/pub", (byId["f"] as FtpServer).path)
        assertEquals("/", (byId["root"] as SmbServer).path)
        job.cancelAndJoin()
    }

    @Test fun `validation matrix rejects bad records per kind`() = runTest {
        val (servers, job) = open(file())
        val goodSmb = SmbServer(id = "s", host = "nas", share = "media", path = "comics", username = "a")
        assertNull(validateSmb(goodSmb))
        assertTrue(validateSmb(goodSmb.copy(host = "  ")) != null)
        assertTrue(validateSmb(goodSmb.copy(share = "")) != null)
        assertTrue(validateSmb(goodSmb.copy(path = "")) != null)
        assertTrue(validateSmb(goodSmb.copy(username = "")) != null)
        assertTrue(validateSmb(goodSmb.copy(port = 0)) != null)
        assertTrue(validateSmb(goodSmb.copy(port = 70000)) != null)
        assertTrue(validateSmb(goodSmb.copy(path = "a/../b")) != null)

        val goodFtp = FtpServer(id = "f", host = "nas", path = "/pub", username = "b", useTls = true)
        assertNull(validateFtp(goodFtp))
        assertTrue(validateFtp(goodFtp.copy(host = "")) != null)
        assertTrue(validateFtp(goodFtp.copy(path = "")) != null)
        assertTrue(validateFtp(goodFtp.copy(username = "")) != null)
        assertTrue(validateFtp(goodFtp.copy(port = 0)) != null)
        assertTrue(validateFtp(goodFtp.copy(port = 70000)) != null)
        assertTrue(validateFtp(goodFtp.copy(path = "/a/../b")) != null)
        // useTls/allowCleartext consistency is not validated: plain FTP without the opt-in
        // still stores, and fails closed at connect time instead.
        assertNull(validateFtp(goodFtp.copy(useTls = false)))

        // Komga/Kavita delegate to validateServerUrl: bad URLs and http-without-opt-in throw
        // before touching the store.
        assertTrue(runCatching { servers.save(KomgaServer(id = "k", baseUrl = "not a url")) }.isFailure)
        val plain = KavitaServer(id = "v", baseUrl = "http://192.168.1.5:5000")
        assertTrue(runCatching { servers.save(plain) }.isFailure)
        assertTrue(servers.current().isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `one corrupt record is skipped, never fatal to its neighbours`() = runTest {
        val f = file()
        val (first, firstJob) = open(f)
        val good = SmbServer(id = "good", host = "nas", share = "media", path = "comics", username = "a")
        first.save(good)
        first.save(FtpServer(id = "alsogood", host = "nas", path = "/pub", username = "b", useTls = true))
        // Hand-corrupt the document: an unknown kind on the second record only. The store
        // file is binary protobuf, so the replacement keeps its length or nothing parses.
        val raw = f.readBytes().toString(Charsets.ISO_8859_1)
        val corrupted = raw.replace("\"kind\":\"FTP\"", "\"kind\":\"FTQ\"")
        f.writeBytes(corrupted.toByteArray(Charsets.ISO_8859_1))
        firstJob.cancelAndJoin()

        val (reopened, job) = open(f)
        assertEquals(listOf("good"), reopened.current().map { it.id })
        job.cancelAndJoin()
    }
}
