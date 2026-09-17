package com.absolutex.remote.smb

import com.hierynomus.mssmb2.SMB2Dialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Pins the signing/encryption posture: reverting the defaults (or flipping one line in
 * [SmbLocation]) must fail here, not on a NAS. Built against smbj-0.15.0, verified by javap.
 */
class SmbConfigTest {

    @Test fun `default requires signing and encryption on smb3 only`() {
        val config = SmbConfigFactory.build(allowUnsigned = false)
        assertEquals(
            setOf(SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1),
            config.supportedDialects,
        )
        assertTrue(config.isSigningRequired)
        assertTrue(config.isEncryptData)
    }

    @Test fun `unsigned opt-out is exactly the four dialects, no more`() {
        val config = SmbConfigFactory.build(allowUnsigned = true)
        assertEquals(
            setOf(
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            ),
            config.supportedDialects,
        )
        assertFalse(config.isSigningRequired)
        assertFalse(config.isEncryptData)
    }

    @Test fun `timeouts bound a stalled server`() {
        val config = SmbConfigFactory.build(allowUnsigned = false)
        assertTrue(config.soTimeout > 0)
        assertTrue(config.readTimeout > 0)
        assertTrue(config.writeTimeout > 0)
        assertTrue(config.transactTimeout > 0)
    }

    @Test fun `both postures wire the bounded handshake factory`() {
        assertTrue(SmbConfigFactory.build(allowUnsigned = false).socketFactory is TimeoutSocketFactory)
        assertTrue(SmbConfigFactory.build(allowUnsigned = true).socketFactory is TimeoutSocketFactory)
    }

    private class RecordingSocket : Socket() {
        var connectTimeoutMs = -1
        var endpoint: SocketAddress? = null
        var boundTo: SocketAddress? = null

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            this.endpoint = endpoint
            this.connectTimeoutMs = timeout
        }

        override fun bind(bindpoint: SocketAddress?) {
            boundTo = bindpoint
        }
    }

    @Test fun `socket factory connects with the configured timeout`() {
        val recording = RecordingSocket()
        TimeoutSocketFactory(1_234) { recording }.createSocket("nas", 445)
        assertEquals(InetSocketAddress("nas", 445), recording.endpoint)
        assertEquals(1_234, recording.connectTimeoutMs)
    }

    @Test fun `socket factory binds the local endpoint before connecting`() {
        val recording = RecordingSocket()
        val local = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)
        TimeoutSocketFactory(1_234) { recording }.createSocket("nas", 445, local.address, local.port)
        assertEquals(local, recording.boundTo)
        assertEquals(1_234, recording.connectTimeoutMs)
    }

    @Test fun `failed connect closes the socket`() {
        // java.net.Socket does not close itself on SocketTimeoutException: without the
        // factory's close, an unreachable NAS leaks one fd per retry.
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                throw SocketTimeoutException("timed out")
            }
        }
        try {
            TimeoutSocketFactory(1_234) { socket }.createSocket("nas", 445)
            fail("expected SocketTimeoutException")
        } catch (expected: SocketTimeoutException) {
            assertTrue(socket.isClosed)
        }
    }

    @Test fun `stalled handshake returns within the timeout`() {
        // Backlog of one, filled and never accepted: the second handshake stalls (or, on
        // loopback stacks that accept anyway, succeeds fast). Either way it must not hang.
        val server = ServerSocket(0, 1)
        try {
            val occupier = Socket()
            try {
                occupier.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                val start = System.nanoTime()
                try {
                    TimeoutSocketFactory(1_000).createSocket("127.0.0.1", server.localPort).close()
                    // Loopback stacks may accept despite the backlog: still bounded, still fast.
                } catch (expected: IOException) {
                    // A true stall surfaces as SocketTimeoutException; a fast refusal
                    // (connection reset on some stacks) is equally bounded. Either way the
                    // elapsed bound below is the assertion — no hang.
                }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue("handshake took ${elapsedMs}ms", elapsedMs < 5_000)
            } finally {
                occupier.close()
            }
        } finally {
            server.close()
        }
    }
}
