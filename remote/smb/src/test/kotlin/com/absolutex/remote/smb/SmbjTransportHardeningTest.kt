package com.absolutex.remote.smb

import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

/**
 * Transport failure policy the fakes in [SmbjTransportTest] cannot express: unchecked SMBJ
 * failures, tampered credential files, concurrent reconnects and stalled connects.
 */
class SmbjTransportHardeningTest {

    private val location = SmbLocation(
        host = "nas",
        share = "comics",
        path = "books/b.cbz",
        port = 445,
        username = "reader",
    )

    private fun credentials(alias: String = "nas"): SmbCredentialStore =
        InMemoryCredentialStore().apply { store(alias, "secret".toCharArray()) }

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
        private val onClose: () -> Unit = {},
    ) : SmbConnection {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)

        override fun openFile(remotePath: String): RemoteFileHandle {
            script(opens.incrementAndGet())
            return handle
        }

        override fun listDir(remotePath: String): List<SmbEntry> = emptyList()

        override fun close() {
            closes.incrementAndGet()
            onClose()
        }
    }

    @Test fun `unchecked share failure surfaces as IOException and reconnects once`() {
        val bytes = ByteArray(16) { it.toByte() }
        // SMBJ reports a dead socket as unchecked SMBRuntimeException, not IOException.
        val connection = ScriptedConnection(ScriptedHandle(bytes), script = { call ->
            if (call == 1) throw SMBRuntimeException("connection reset")
        })
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                return connection
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        assertTrue(transport.readAt("books/b.cbz", 0, 16).contentEquals(bytes))
        assertEquals(2, connects)
    }

    @Test fun `persistent unchecked failure propagates as IOException with context`() {
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            throw SMBRuntimeException("session invalidated")
        })
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                return connection
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            // One reconnect, then the second failure carries the first — never an unchecked
            // exception, never a loop.
            assertEquals(2, connects)
            assertEquals(1, expected.suppressed.size)
        }
    }

    @Test fun `persistent unchecked failure in stat propagates as IOException`() {
        val connection = ScriptedConnection(ScriptedHandle(ByteArray(0)), script = {
            throw SMBRuntimeException("session invalidated")
        })
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                return connection
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        try {
            transport.sizeBytes("books/b.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals(2, connects)
            assertEquals(1, expected.suppressed.size)
        }
    }

    @Test fun `tampered credential file latches as IOException without retry storm`() {
        var retrieves = 0
        val tampered = object : SmbCredentialStore {
            override fun store(alias: String, password: CharArray) = Unit

            override fun retrieve(alias: String): CharArray {
                retrieves++
                throw AEADBadTagException()
            }

            override fun clear(alias: String) = Unit
        }
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                throw IOException("unreachable")
            }
        }
        val transport = SmbjTransport(location, tampered, "nas", connector)
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("credentials") == true)
        }
        // The second read rethrows the remembered failure without re-decrypting.
        try {
            transport.readAt("books/b.cbz", 0, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("authentication failed") == true)
        }
        // Decrypted once, never dialled: the crypto failure latched like any auth failure.
        assertEquals(1, retrieves)
        assertEquals(0, connects)
    }

    @Test fun `concurrent failure drops only the failed connection`() {
        val bytes = ByteArray(16) { it.toByte() }
        val c2Ready = CountDownLatch(1)
        // Call 1 is T2, parked inside the dead share; call 2 is T1, failing fast. T2 wakes
        // only after T1 has established C2 — so T2's drop must see the mismatch and spare it.
        val c1 = ScriptedConnection(ScriptedHandle(bytes), script = { call ->
            if (call == 1) {
                assertTrue(c2Ready.await(10, TimeUnit.SECONDS))
            }
            throw IOException("stale share")
        })
        val c2 = ScriptedConnection(ScriptedHandle(bytes))
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                if (connects == 2) {
                    c2Ready.countDown()
                    return c2
                }
                return c1
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        var second: Result<ByteArray>? = null
        val parked = thread { second = runCatching { transport.readAt("books/b.cbz", 0, 16) } }
        waitFor("parked reader") { c1.opens.get() >= 1 }
        var first: Result<ByteArray>? = null
        val racing = thread { first = runCatching { transport.readAt("books/b.cbz", 0, 16) } }
        racing.join(10000)
        parked.join(10000)
        assertFalse(racing.isAlive)
        assertFalse(parked.isAlive)
        assertTrue(first?.getOrNull()?.contentEquals(bytes) == true)
        assertTrue(second?.getOrNull()?.contentEquals(bytes) == true)
        // The stale share closed exactly once; the healthy replacement never closed.
        assertEquals(1, c1.closes.get())
        assertEquals(0, c2.closes.get())
        assertEquals(2, connects)
    }

    @Test fun `teardown closes outside the lock`() {
        val bytes = ByteArray(16) { it.toByte() }
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        // TREE_DISCONNECT is a network round trip: on a half-dead session this close blocks
        // up to the socket timeout, and no other reader may wait on the lock behind it.
        val c1 = ScriptedConnection(
            ScriptedHandle(bytes),
            script = { throw IOException("dead share") },
            onClose = {
                closeEntered.countDown()
                assertTrue(releaseClose.await(10, TimeUnit.SECONDS))
            },
        )
        val c2 = ScriptedConnection(ScriptedHandle(bytes))
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                return if (connects == 1) c1 else c2
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        var first: Result<ByteArray>? = null
        val racing = thread { first = runCatching { transport.readAt("books/b.cbz", 0, 16) } }
        assertTrue(closeEntered.await(10, TimeUnit.SECONDS))
        // The teardown is still blocked inside C1's close — yet this read proceeds via C2.
        val start = System.nanoTime()
        assertTrue(transport.readAt("books/b.cbz", 0, 16).contentEquals(bytes))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        releaseClose.countDown()
        racing.join(10_000)
        assertFalse(racing.isAlive)
        assertTrue("read blocked for ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue(first?.getOrNull()?.contentEquals(bytes) == true)
        assertEquals(1, c1.closes.get())
        assertEquals(0, c2.closes.get())
        assertEquals(2, connects)
    }

    @Test fun `transport close returns during another teardown`() {
        val bytes = ByteArray(16) { it.toByte() }
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val c1 = ScriptedConnection(
            ScriptedHandle(bytes),
            script = { throw IOException("dead share") },
            onClose = {
                closeEntered.countDown()
                assertTrue(releaseClose.await(10, TimeUnit.SECONDS))
            },
        )
        var connects = 0
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                connects++
                return c1
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        val racing = thread { runCatching { transport.readAt("books/b.cbz", 0, 16) } }
        assertTrue(closeEntered.await(10, TimeUnit.SECONDS))
        // The drop already swapped C1 out, so close only marks and returns — it never waits
        // for the racing teardown, and the retry after it fails closed instead of dialling.
        val start = System.nanoTime()
        transport.close()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("close blocked for ${elapsedMs}ms", elapsedMs < 2_000)
        releaseClose.countDown()
        racing.join(10_000)
        assertFalse(racing.isAlive)
        assertEquals(1, connects)
    }

    private class CountingConnector : SmbConnector {
        val connects = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)
        private val maxLock = Any()
        var maxInFlight = 0

        override fun connect(password: CharArray): SmbConnection {
            connects.incrementAndGet()
            val current = inFlight.incrementAndGet()
            try {
                synchronized(maxLock) {
                    maxInFlight = maxOf(maxInFlight, current)
                }
                throw IOException("bad password")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test fun `concurrent cold logons single-flight behind one attempt`() {
        val connector = CountingConnector()
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<Result<ByteArray>>())
        val workers = (1..8).map {
            thread { outcomes += runCatching { transport.readAt("books/b.cbz", 0, 8) } }
        }
        workers.forEach { it.join(10_000) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(8, outcomes.size)
        // One logon, eight identical IOExceptions — never eight NTLM failures, and never the
        // latch's self-suppression IllegalArgumentException.
        assertTrue(outcomes.all { it.exceptionOrNull() is IOException })
        assertEquals(1, connector.connects.get())
        assertEquals(1, connector.maxInFlight)
    }

    @Test fun `close returns promptly while a connect is stalled`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connector = object : SmbConnector {
            override fun connect(password: CharArray): SmbConnection {
                entered.countDown()
                assertTrue(release.await(15, TimeUnit.SECONDS))
                return ScriptedConnection(ScriptedHandle(ByteArray(8)))
            }
        }
        val transport = SmbjTransport(location, credentials(), "nas", connector)
        var outcome: Result<ByteArray>? = null
        val worker = thread { outcome = runCatching { transport.readAt("books/b.cbz", 0, 8) } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val start = System.nanoTime()
        transport.close()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        release.countDown()
        worker.join(10000)
        assertFalse(worker.isAlive)
        // Close never waits for the stalled logon; the logon, completing after close,
        // is refused at install and the read fails closed.
        assertTrue("close blocked for ${elapsedMs}ms", elapsedMs < 2000)
        assertTrue(outcome?.exceptionOrNull() is IOException)
    }

    private fun waitFor(what: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("$what never arrived", ready())
    }
}
