package com.absolutex.remote.ftp

import com.absolutex.remote.core.CredentialExpiredException
import com.absolutex.remote.core.TransportAuthException
import com.absolutex.remote.core.TransientExhaustedException
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase F resilience for FTP: bounded retry with reconnect-and-resume, typed auth and
 * expiry, definitive 5xx handling, invalidation.
 *
 * A scripted in-memory client fails a set number of transfers then succeeds
 * (fail-transient-twice, always-auth, rotation, 550) — no live server. Each test names
 * what it proves and what it fails without, as the mutation proof the lane demands.
 */
class FtpResilienceTest {

    private class ScriptedFtpClient(val bytes: ByteArray) : FTPClient() {
        var connects = 0
        var retrieveCalls = 0
        var loginCalls = 0
        var mlistCalls = 0
        var listCalls = 0
        val restarts = ArrayList<Long>()

        var loginRefused = false
        var failRetrievals = 0
        var refuseRetr = false
        var refuseList = false
        var scriptedReplyCode = 0
        var failMlistOnce = false
        var failListOnce = false

        override fun connect(host: String, port: Int) {
            connects++
        }

        override fun setConnectTimeout(timeout: Int) = Unit

        override fun setSoTimeout(timeout: Int) = Unit

        override fun login(username: String, password: String): Boolean {
            loginCalls++
            return !loginRefused
        }

        override fun logout(): Boolean = true

        override fun disconnect() = Unit

        override fun isConnected(): Boolean = true

        override fun setFileType(fileType: Int): Boolean = true

        override fun setRestartOffset(offset: Long) {
            restarts += offset
        }

        override fun retrieveFileStream(path: String): InputStream? {
            retrieveCalls++
            if (failRetrievals > 0) {
                failRetrievals--
                throw SocketException("connection reset")
            }
            if (refuseRetr) return null
            val from = (restarts.lastOrNull() ?: 0L).coerceAtLeast(0).toInt()
            return ByteArrayInputStream(bytes.copyOfRange(from, bytes.size))
        }

        override fun completePendingCommand(): Boolean = true

        override fun getReplyCode(): Int = scriptedReplyCode

        override fun mlistFile(path: String): FTPFile {
            mlistCalls++
            if (failMlistOnce) {
                failMlistOnce = false
                throw SocketException("connection reset")
            }
            val file = FTPFile()
            file.name = path
            file.setSize(bytes.size.toLong())
            return file
        }

        override fun listFiles(pathname: String): Array<FTPFile> {
            listCalls++
            if (failListOnce) {
                failListOnce = false
                throw SocketException("connection reset")
            }
            if (refuseList) return emptyArray()
            val file = FTPFile()
            file.name = "book.cbz"
            file.setSize(bytes.size.toLong())
            return arrayOf(file)
        }
    }

    private val payload = ByteArray(4096) { it.toByte() }
    private val location = FtpLocation("h", 21, "u", "/b.cbz", false)

    private fun transport(client: ScriptedFtpClient): CommonsNetFtpTransport =
        CommonsNetFtpTransport(location, { "pw".toCharArray() }, { client })

    @Test fun `fail-transient-twice-then-succeed takes exactly three attempts`() {
        // Proves the retry with reconnect-and-resume: without it the first reset
        // escapes; each attempt reconnects and re-issues REST from the same offset.
        val client = ScriptedFtpClient(payload).apply { failRetrievals = 2 }
        val got = transport(client).readAt("/b.cbz", 100, 500)
        assertArrayEquals(payload.copyOfRange(100, 600), got)
        assertEquals(3, client.retrieveCalls)
        assertEquals(3, client.connects)
        assertEquals(listOf(100L, 100L, 100L), client.restarts)
    }

    @Test fun `always-transient exhausts typed after three attempts`() {
        // Proves the bound: without it the loop never terminates; the typed
        // exhaustion (not the last socket error) is what the reader matches on.
        val client = ScriptedFtpClient(payload).apply { failRetrievals = 10 }
        try {
            transport(client).readAt("/b.cbz", 0, 10)
            throw AssertionError("expected TransientExhaustedException")
        } catch (expected: TransientExhaustedException) {
            assertEquals(3, client.retrieveCalls)
            assertEquals(3, client.connects)
            assertTrue(expected.cause is SocketException)
            assertEquals(2, expected.suppressed.size)
        }
    }

    @Test fun `first login refused is auth without retry`() {
        // Proves the lockout guard: a retried refused login would show loginCalls 3
        // and triple the password attempts against the server. Message prefix kept
        // for the probe's AuthFailed mapping.
        val client = ScriptedFtpClient(payload).apply { loginRefused = true }
        try {
            transport(client).readAt("/b.cbz", 0, 10)
            throw AssertionError("expected TransportAuthException")
        } catch (expected: TransportAuthException) {
            assertTrue(expected.message?.startsWith("FTP login refused") == true)
        }
        assertEquals(1, client.loginCalls)
        assertEquals(1, client.connects)
    }

    @Test fun `login refused after success is expiry`() {
        // Proves rotation detection: the same refusal that reads as auth on a fresh
        // transport reads as sign-in-again once a login already succeeded. Without
        // the ever-authenticated tracking the reader would offer retry, not sign-in.
        val client = ScriptedFtpClient(payload)
        val live = transport(client)
        assertArrayEquals(payload.copyOfRange(0, 8), live.readAt("/b.cbz", 0, 8))
        client.loginRefused = true
        live.invalidate()
        try {
            live.readAt("/b.cbz", 0, 8)
            throw AssertionError("expected CredentialExpiredException")
        } catch (expected: CredentialExpiredException) {
            assertTrue(expected.message?.startsWith("FTP login refused") == true)
        }
        assertEquals(2, client.loginCalls)
    }

    @Test fun `550 on RETR is missing without retry`() {
        // Proves definitive replies skip the loop: a missing file costs one RETR, not
        // three with backoff. Without reply classification this would exhaust.
        val client = ScriptedFtpClient(payload).apply {
            refuseRetr = true
            scriptedReplyCode = 550
        }
        try {
            transport(client).readAt("/b.cbz", 0, 10)
            throw AssertionError("expected FileNotFoundException")
        } catch (expected: FileNotFoundException) {
            assertTrue(expected.message?.contains("/b.cbz") == true)
        }
        assertEquals(1, client.retrieveCalls)
        assertEquals(1, client.connects)
    }

    @Test fun `missing list path keeps its probe message without retry on 5xx`() {
        // Proves the probe contract survives typing: the 550 list failure keeps the
        // "cannot list" prefix mapFtpFailure matches for NotFound, typed as missing.
        val client = ScriptedFtpClient(payload).apply {
            refuseList = true
            scriptedReplyCode = 550
        }
        try {
            transport(client).listDir("/nope")
            throw AssertionError("expected FileNotFoundException")
        } catch (expected: FileNotFoundException) {
            assertTrue(expected.message?.contains("cannot list") == true)
        }
        assertEquals(1, client.listCalls)
    }

    @Test fun `sizeBytes retries a transient stat failure`() {
        // Proves size (cheap, and on the open path) shares the retry budget: a reset
        // MLST falls back to LIST, so both listings must fail transiently to spend an
        // attempt — then the reconnect reports the size on the second attempt.
        val client = ScriptedFtpClient(payload).apply {
            failMlistOnce = true
            failListOnce = true
        }
        assertEquals(payload.size.toLong(), transport(client).sizeBytes("/b.cbz"))
        assertEquals(2, client.mlistCalls)
        assertEquals(2, client.connects)
    }

    @Test fun `invalidate drops the session so the next call reconnects`() {
        // Proves the network-change hook: after invalidate the next read dials again
        // (two connects) instead of speaking to a stale control connection.
        val client = ScriptedFtpClient(payload)
        val live = transport(client)
        assertArrayEquals(payload.copyOfRange(0, 8), live.readAt("/b.cbz", 0, 8))
        live.invalidate()
        assertArrayEquals(payload.copyOfRange(0, 8), live.readAt("/b.cbz", 0, 8))
        assertEquals(2, client.connects)
        live.close()
    }

    @Test fun `refused login disconnects instead of leaking the socket`() {
        // Proves the refused fresh connection is torn down: without the disconnect
        // the dialled socket would leak on every bad-password attempt.
        val client = ScriptedFtpClient(payload).apply { loginRefused = true }
        try {
            transport(client).readAt("/b.cbz", 0, 10)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected is TransportAuthException)
        }
        assertEquals(1, client.connects)
    }
}
