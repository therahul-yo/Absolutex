package com.absolutex.remote.smb

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransientExhaustedException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase F resilience: bounded retry with backoff, typed failures, invalidation.
 *
 * Scripted connectors fail a set number of times then succeed (fail-transient-once,
 * always-auth, rotation, missing) — no live NAS. Each test names what it proves and
 * what it fails without, as the mutation proof the lane standard demands.
 */
class SmbjResilienceTest {

    private val location = SmbLocation(
        host = "nas",
        share = "comics",
        path = "books/b.cbz",
        port = 445,
        username = "reader",
    )

    private fun credentials(alias: String = "nas"): SmbCredentialStore =
        InMemoryCredentialStore().apply { store(alias, "secret".toCharArray()) }

    private fun smbApi(status: Long): SMBApiException =
        SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)

    /**
     * Asserts [block] throws [T] and returns it. One helper instead of ten
     * try/throw-AssertionError/catch blocks: the throw budget stays intact no matter
     * how many failure shapes a test pins.
     */
    private inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e")
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }

    private class ScriptedHandle(private val bytes: ByteArray) : RemoteFileHandle {
        override val length: Long get() = bytes.size.toLong()

        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val take = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + take)
            return take
        }

        override fun close() = Unit
    }

    private class ScriptedConnection(
        private val handle: RemoteFileHandle,
        private val script: (call: Int) -> Unit = {},
    ) : SmbConnection {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)

        override fun openFile(remotePath: String): RemoteFileHandle {
            script(opens.incrementAndGet())
            return handle
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private fun transport(connection: ScriptedConnection, connects: AtomicInteger): SmbjTransport {
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects.incrementAndGet()
                return connection
            }
        }
        return SmbjTransport(location, credentials(), "nas", connector)
    }

    @Test fun `transient read failure reconnects once and succeeds`() {
        // Proves the retry: without it the first throw escapes and the data assertion
        // fails. The reconnect-once ceiling holds — exactly two connects, one backoff.
        val bytes = ByteArray(16) { it.toByte() }
        val connection = ScriptedConnection(ScriptedHandle(bytes), script = { call ->
            if (call == 1) throw SocketException("connection reset")
        })
        val connects = AtomicInteger(0)
        val got = transport(connection, connects).readAt("books/b.cbz", 0, 16)
        assertTrue(got.contentEquals(bytes))
        assertEquals(2, connects.get())
    }

    @Test fun `persistent transient exhausts typed after exactly two connects`() {
        // Proves the bound and the typed exhaustion: without retry this throws the
        // bare first failure after one connect; with unbounded retry it never stops.
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            throw SocketException("connection reset")
        })
        val connects = AtomicInteger(0)
        try {
            transport(connection, connects).readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected TransientExhaustedException")
        } catch (expected: TransientExhaustedException) {
            assertEquals(2, connects.get())
            assertEquals(1, expected.suppressed.size)
            // The cause is the second attempt's raw socket failure (only the unchecked
            // SMBJ surface gets the "smb read failed" wrap); the first is suppressed.
            assertTrue(expected.cause is SocketException)
        }
    }

    @Test fun `logon failure never retried and typed as auth`() {
        // Proves the lockout guard: a retried bad password would dial twice and the
        // connects assertion would fail. The latch still holds for the second read.
        val connects = AtomicInteger(0)
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects.incrementAndGet()
                throw IOException("smb connect failed", smbApi(0xC000006DL))
            }
        }
        val live = SmbjTransport(location, credentials(), "nas", connector)
        try {
            live.readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected TransportAuthException")
        } catch (expected: TransportAuthException) {
            assertTrue(expected.message?.contains("authentication failed") == true)
        }
        assertEquals(1, connects.get())
        try {
            live.readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected latch IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("authentication failed") == true)
        }
        assertEquals(1, connects.get())
    }

    @Test fun `rotation mid-session surfaces sign-in-again and sticks`() {
        // Proves expiry detection: an established session refused with logon-failure
        // is rotation, not a dead socket — no retry, typed expiry carrying the alias.
        // The reconnect then fails the same way and stays expiry (stale flag), so the
        // reader offers sign-in instead of an unreachable error.
        val bytes = ByteArray(16) { it.toByte() }
        val dead = ScriptedConnection(ScriptedHandle(bytes), script = {
            throw smbApi(0xC000006DL)
        })
        val connects = AtomicInteger(0)
        val authConnector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects.incrementAndGet()
                if (connects.get() == 1) return dead
                throw IOException("smb connect failed", smbApi(0xC000006DL))
            }
        }
        val live = SmbjTransport(location, credentials(), "nas", authConnector)
        assertEquals("nas", failsWith<CredentialExpiredException> {
            live.readAt("books/b.cbz", 0, 8)
        }.serverId)
        assertEquals("nas", failsWith<CredentialExpiredException> {
            live.readAt("books/b.cbz", 0, 8)
        }.serverId)
        assertEquals(2, connects.get())
    }

    @Test fun `missing file never retried and typed as not found`() {
        // Proves definitive failures skip the loop: the old reconnect-once would dial
        // twice here; now a missing object costs one attempt and a typed error.
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            throw smbApi(0xC0000034L)
        })
        val connects = AtomicInteger(0)
        try {
            transport(connection, connects).readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected FileNotFoundException")
        } catch (expected: FileNotFoundException) {
            assertTrue(expected.message?.contains("books/b.cbz") == true)
        }
        assertEquals(1, connects.get())
    }

    @Test fun `denied handle never retried`() {
        // Proves permissions are definitive: retrying a denied open cannot succeed,
        // so one attempt and a permanent error with no reconnect.
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            throw smbApi(0xC0000022L)
        })
        val connects = AtomicInteger(0)
        try {
            transport(connection, connects).readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("access denied") == true)
        }
        assertEquals(1, connects.get())
    }

    @Test fun `invalidate drops the live session without latching`() {
        // Proves the network-change hook: after invalidate the next read reconnects
        // (two connects, stale closed once) and auth state is untouched — a later
        // bad-password logon still latches as auth, not as rotation.
        val bytes = ByteArray(16) { it.toByte() }
        val connection = ScriptedConnection(ScriptedHandle(bytes))
        val connects = AtomicInteger(0)
        val live = transport(connection, connects)
        assertTrue(live.readAt("books/b.cbz", 0, 16).contentEquals(bytes))
        live.invalidate()
        assertEquals(1, connection.closes.get())
        assertTrue(live.readAt("books/b.cbz", 0, 16).contentEquals(bytes))
        assertEquals(2, connects.get())
        live.close()
    }

    @Test fun `sizeBytes shares the read retry budget`() {
        // Proves size (cheap, and on the open path) reconnects like reads do.
        val bytes = ByteArray(16) { it.toByte() }
        val connection = ScriptedConnection(ScriptedHandle(bytes), script = { call ->
            if (call == 1) throw SocketException("connection reset")
        })
        val connects = AtomicInteger(0)
        assertEquals(16L, transport(connection, connects).sizeBytes("books/b.cbz"))
        assertEquals(2, connects.get())
    }

    @Test fun `short stream is transient and resumes`() {
        // Proves EOF mid-stream retries: a handle serving half the range then EOF
        // reconnects and completes. Without the retry the short read escapes.
        val bytes = ByteArray(16) { it.toByte() }
        val flaky = object : RemoteFileHandle {
            var calls = 0
            override val length: Long get() = bytes.size.toLong()
            override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
                calls++
                val take = when (calls) {
                    1 -> {
                        bytes.copyInto(buffer, bufferOffset, 0, 4)
                        4
                    }
                    2 -> -1
                    else -> {
                        val rest = minOf(length, bytes.size - fileOffset.toInt())
                        bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + rest)
                        rest
                    }
                }
                return take
            }
            override fun close() = Unit
        }
        val connection = ScriptedConnection(flaky)
        val connects = AtomicInteger(0)
        val got = transport(connection, connects).readAt("books/b.cbz", 0, 16)
        assertTrue(got.contentEquals(bytes))
        assertEquals(2, connects.get())
    }

    @Test fun `interrupted read keeps its shape and never retries`() {
        // Proves cancellation is not transient: an interrupted read must surface
        // immediately (one connect) instead of sleeping through backoff.
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(8)), script = {
            throw InterruptedException("interrupted during read")
        })
        val connects = AtomicInteger(0)
        try {
            transport(connection, connects).readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("smb read failed") == true)
        }
        assertEquals(1, connects.get())
    }

    @Test fun `exhaustion evidence survives when the second failure is definitive`() {
        // Proves mixed failures stay truthful: a transient first failure followed by
        // a definitive second propagates the definitive error with the first
        // suppressed — not an exhaustion error for a file that simply vanished.
        var calls = 0
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            calls++
            if (calls == 1) throw SocketException("connection reset")
            throw smbApi(0xC0000034L)
        })
        val connects = AtomicInteger(0)
        val failure = failsWith<FileNotFoundException> {
            transport(connection, connects).readAt("books/b.cbz", 0, 8)
        }
        assertEquals(1, failure.suppressed.size)
        assertEquals(2, connects.get())
    }

    @Test fun `connect-time access denied is auth, file-time is permanent`() {
        // Proves the setup/read split shares one status table: the same NT status at
        // logon means bad credentials (latch, no retry), on a handle means
        // permissions (no retry, no latch involved).
        val denied = smbApi(0xC0000022L)
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                throw IOException("smb connect failed", denied)
            }
        }
        val live = SmbjTransport(location, credentials(), "nas", connector)
        try {
            live.readAt("books/b.cbz", 0, 8)
            throw AssertionError("expected TransportAuthException")
        } catch (expected: TransportAuthException) {
            assertTrue(expected.message?.contains("authentication failed") == true)
        }
    }
}
