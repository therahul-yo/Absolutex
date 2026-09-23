package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/**
 * Transport failure policy without a network: a fake connector counts every logon attempt,
 * so "failed login is not retried" is an assertion on a counter, not a stopwatch.
 *
 * No in-process SMB2 server exists for JVM tests (unlike FTP's Apache FtpServer), so the
 * production connector's wire behaviour is code-reviewed plus lead-verified on a real NAS;
 * everything decision-shaped — latch, reconnect-once, terminal close — is pinned here.
 */
class SmbjTransportTest {

    private val location = SmbLocation(
        host = "nas",
        share = "comics",
        path = "books/b.cbz",
        port = 445,
        username = "reader",
    )

    private class FakeHandle(private val bytes: ByteArray) : RemoteFileHandle {
        override val length: Long get() = bytes.size.toLong()

        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val take = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + take)
            return take
        }

        override fun close() = Unit
    }

    private class FakeConnection(
        private val handle: RemoteFileHandle,
        private val onOpen: () -> Unit = {},
    ) : SmbConnection {
        var opens = 0
        var closes = 0

        override fun openFile(remotePath: String): RemoteFileHandle {
            opens++
            onOpen()
            return handle
        }

        override fun close() {
            closes++
        }
    }

    private class FakeConnector(
        var failures: Int,
        val failure: IOException = IOException("logon failure"),
        val connection: FakeConnection = FakeConnection(FakeHandle(ByteArray(0))),
    ) : SmbConnector {
        var connects = 0

        override fun connect(password: CharArray): SmbConnection {
            connects++
            if (failures > 0) {
                failures--
                throw failure
            }
            return connection
        }
    }

    private fun transport(
        connector: FakeConnector,
        alias: String = "nas",
        credentials: SmbCredentialStore = InMemoryCredentialStore().apply {
            store(alias, "secret".toCharArray())
        },
    ): SmbjTransport = SmbjTransport(location, credentials, alias, connector)

    private fun failureOf(op: () -> Unit): IOException {
        return try {
            op()
            fail("expected IOException")
            throw AssertionError("unreachable")
        } catch (e: IOException) {
            e
        }
    }

    @Test fun `a failed login is remembered and never retried`() {
        val connector = FakeConnector(failures = Int.MAX_VALUE)
        val transport = transport(connector)
        val first = failureOf { transport.readAt("books/b.cbz", 0, 8) }
        val second = failureOf { transport.readAt("books/b.cbz", 0, 8) }
        // The remembered failure, not a second logon: with a stale password and five
        // queued pages this is the difference between one failure and a locked account.
        assertEquals(1, connector.connects)
        // Fresh instances: rethrowing the stored error itself makes the retry path call
        // e.addSuppressed(e), which is IllegalArgumentException, not IOException.
        assertTrue(first !== second)
        assertTrue(first.message?.contains("logon failure") == true)
        assertTrue(second.message?.contains("authentication failed") == true)
    }

    @Test fun `missing credentials fail without touching the network`() {
        val connector = FakeConnector(failures = 0)
        val transport = transport(connector, credentials = InMemoryCredentialStore())
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no stored credentials") == true)
        }
        assertEquals(0, connector.connects)
    }

    @Test fun `an io failure on a live share reconnects once and succeeds`() {
        val bytes = ByteArray(16) { it.toByte() }
        var opens = 0
        val connection = FakeConnection(FakeHandle(bytes)) {
            opens++
            // Dead-socket shape (typed reset, as production read failures arrive):
            // this test pins the reconnect mechanics, not failure classification.
            if (opens == 1) throw SocketException("connection reset")
        }
        val connector = FakeConnector(failures = 0, connection = connection)
        val transport = transport(connector)
        assertTrue(transport.readAt("books/b.cbz", 0, 16).contentEquals(bytes))
        // Initial share plus the one revalidation: a NAS reboot costs one reconnect, not a storm.
        assertEquals(2, connector.connects)
    }

    @Test fun `a second failure propagates instead of looping`() {
        // Same dead-socket shape as above: persistent transient failures reconnect
        // once, then the exhaustion error propagates with the first suppressed.
        val connection = FakeConnection(FakeHandle(ByteArray(0))) { throw SocketException("server gone") }
        val connector = FakeConnector(failures = 0, connection = connection)
        val transport = transport(connector)
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals(2, connector.connects)
        }
    }

    @Test fun `reads after close throw without connecting`() {
        val connector = FakeConnector(failures = 0)
        val transport = transport(connector)
        transport.close()
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("closed") == true)
        }
        assertEquals(0, connector.connects)
    }

    @Test fun `close after a failed logon is safe`() {
        val connector = FakeConnector(failures = 1)
        val transport = transport(connector)
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            transport.close()
        }
        assertEquals(1, connector.connects)
    }

    @Test fun `size uses one handle and reports length`() {
        val connector = FakeConnector(
            failures = 0,
            connection = FakeConnection(FakeHandle(ByteArray(1234))),
        )
        val transport = transport(connector)
        assertEquals(1234L, transport.sizeBytes("books/b.cbz"))
        assertEquals(1, connector.connection.opens)
    }
}
