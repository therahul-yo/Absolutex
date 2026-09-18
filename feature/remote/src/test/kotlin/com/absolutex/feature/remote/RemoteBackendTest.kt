package com.absolutex.feature.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.absolutex.remote.smb.RemoteFileHandle
import com.absolutex.remote.smb.SmbConnection
import com.absolutex.remote.smb.SmbLocation
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.SmbServer
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.SyncSecrets
import com.absolutex.remote.sync.InMemoryCredentialStore
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SMB failure mapping over real smbj exception types, plus backend resolution without a
 * network: construction never dials, so every transport here is built, never connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteBackendTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun smbApi(status: Long) =
        SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)

    private class FakeShare(private val onClose: () -> Unit = {}) : SmbConnection {
        override fun openFile(remotePath: String): RemoteFileHandle {
            throw IOException("unused in connection tests")
        }

        override fun close() {
            onClose()
        }
    }

    private fun location() = SmbLocation("nas", "comics", "/books/b.cbz", 445, "reader")

    @Test fun `successful connect is ok and closes the share`() {
        var closed = false
        var seen: CharArray? = null
        val result = SmbConnectionTester().test(location(), "pw".toCharArray()) {
            seen = it.copyOf()
            FakeShare { closed = true }
        }
        assertEquals(ConnectionResult.Ok, result)
        assertTrue(closed)
        assertTrue(seen?.contentEquals("pw".toCharArray()) == true)
    }

    @Test fun `logon failure and access denied are auth failed`() {
        assertEquals(
            ConnectionResult.AuthFailed,
            SmbConnectionTester().test(location(), "pw".toCharArray()) { throw smbApi(0xC000006DL) },
        )
        assertEquals(
            ConnectionResult.AuthFailed,
            SmbConnectionTester().test(location(), "pw".toCharArray()) { throw smbApi(0xC0000022L) },
        )
    }

    @Test fun `missing share is not found`() {
        assertEquals(
            ConnectionResult.NotFound,
            SmbConnectionTester().test(location(), "pw".toCharArray()) { throw smbApi(0xC00000CCL) },
        )
    }

    @Test fun `signing refusal is security refused`() {
        assertEquals(
            ConnectionResult.SecurityRefused,
            SmbConnectionTester().test(location(), "pw".toCharArray()) {
                throw SMB2GuestSigningRequiredException()
            },
        )
    }

    @Test fun `unreachable hosts and timeouts are unreachable`() {
        val failures = listOf<Throwable>(
            UnknownHostException("nas"),
            SocketTimeoutException("timed out"),
            IOException("mystery failure"),
        )
        for (failure in failures) {
            assertEquals(
                ConnectionResult.Unreachable,
                SmbConnectionTester().test(location(), "pw".toCharArray()) { throw failure },
            )
        }
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

    private fun dataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File(tmp.root, name) },
    )

    private suspend fun seeded(): Triple<RemoteServers, SyncSecrets, RemoteBackendResolver> {
        val servers = RemoteServers(dataStore("remote.preferences_pb"))
        val secrets = SyncSecrets(InMemoryCredentialStore())
        servers.save(SmbServer("smb", "nas", "comics", "/books", 445, "u"))
        secrets.saveSmbPassword("smb", "pw".toCharArray())
        servers.save(FtpServer("ftp", "nas", 21, "/pub", "u", useTls = false))
        secrets.saveFtpPassword("ftp", "pw".toCharArray())
        servers.save(KomgaServer("komga", "https://k.lan", username = "u"))
        val resolver = RemoteBackendResolver(servers, SmbBookBackend(secrets), FtpBookBackend(secrets))
        return Triple(servers, secrets, resolver)
    }

    @Test fun `smb record maps to its location absolutely`() {
        val server = SmbServer("s", "nas", "comics", "/books", 445, "u", allowUnsigned = true)
        val location = server.toLocation("/books/b.cbz")
        assertEquals("nas", location.host)
        assertEquals("comics", location.share)
        assertEquals("/books/b.cbz", location.path)
        assertEquals(445, location.port)
        assertEquals("u", location.username)
        assertEquals(true, location.allowUnsigned)
        assertEquals("/books/b.cbz", absolutePath("/books/b.cbz"))
        assertEquals("/books/b.cbz", absolutePath("books/b.cbz"))
    }

    @Test fun `resolver builds both transports without dialling`() = runTest {
        val (_, _, resolver) = seeded()
        val smb = resolver.transportFor("smb", "/books/b.cbz")
        smb.close()
        val ftp = resolver.transportFor("ftp", "/pub/b.cbz")
        ftp.close()
    }

    @Test fun `resolver rejects unknown sync and secretless servers`() = runTest {
        val (_, _, resolver) = seeded()
        try {
            resolver.transportFor("nope", "/b.cbz")
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("unknown") == true)
        }
        try {
            resolver.transportFor("komga", "/b.cbz")
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no files") == true)
        }
        val servers = RemoteServers(dataStore("other.preferences_pb"))
        val secrets = SyncSecrets(InMemoryCredentialStore())
        servers.save(SmbServer("nopw", "nas", "comics", "/b", 445, "u"))
        val bare = RemoteBackendResolver(servers, SmbBookBackend(secrets), FtpBookBackend(secrets))
        try {
            bare.transportFor("nopw", "/b/b.cbz")
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("password") == true)
        }
    }
}
