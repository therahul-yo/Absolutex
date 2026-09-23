package com.absolutex.remote.core

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader failure contract (Phase F item 4): what the reader can rely on without
 * touching `feature/reader` — same page retained, retry on demand, never a crash.
 *
 * Each test names the contract clause it pins, so a failure reads as a broken promise
 * rather than a broken assertion.
 */
class CoreComicSourceResilienceTest {

    // Six incompressible 100 KiB pages (java.util.Random is specified, so seeded
    // output is deterministic across JVMs). Open caches block 0 (the 4-byte magic
    // sniff) and the tail (the ~64 KiB EOCD scan), so a middle page's bytes are the
    // only ones guaranteed a fresh transport read on first open: periodic fixture
    // bytes would deflate small enough to sit fully resident after open, and the
    // scripted failure would never fire.
    private val seeds = listOf(11, 12, 13, 14, 15, 16)
    private val pageSize = 100 * 1024
    private val middlePage = 2

    private fun randomPage(seed: Int): ByteArray {
        val bytes = ByteArray(pageSize)
        java.util.Random(seed.toLong()).nextBytes(bytes)
        return bytes
    }

    private fun archive(): ByteArray =
        ZipBytes.cbz(*seeds.mapIndexed { index, seed ->
            "page%02d.jpg".format(index + 1) to randomPage(seed)
        }.toTypedArray())

    /** Scripted transport: fails [failuresLeft] reads with [failure], then serves. */
    private class ScriptedTransport(bytes: ByteArray) : RangeTransport {
        var backing = bytes
        var failuresLeft = 0
        var failure: IOException = IOException("boom")
        var reads = 0
        var closes = 0

        override fun sizeBytes(): Long = backing.size.toLong()

        override fun readAt(offset: Long, length: Int): ByteArray {
            reads++
            if (failuresLeft > 0) {
                failuresLeft--
                throw failure
            }
            return backing.copyOfRange(offset.toInt(), (offset + length).toInt())
        }

        override fun close() {
            closes++
        }
    }

    private fun openAndFailOnce(failure: IOException): Pair<CoreComicSource, ScriptedTransport> {
        val transport = ScriptedTransport(archive())
        val source = (CoreComicSource.open(transport, "book.cbz") as RemoteOpenResult.Ready).source
            as CoreComicSource
        transport.failuresLeft = 1
        transport.failure = failure
        return source to transport
    }

    @Test fun `a failed page keeps its place and retries on demand`() {
        // Clause: same page retained, retry on demand. The failure caches nothing, so
        // reopening the same index after recovery serves the page — the reader holds
        // its position and offers retry instead of losing the book.
        val exhausted = TransientExhaustedException("transient failure after 3 attempts")
        val (source, transport) = openAndFailOnce(exhausted)
        val readsAfterOpen = transport.reads
        try {
            source.openPage(middlePage).close()
            throw AssertionError("expected TransientExhaustedException")
        } catch (expected: TransientExhaustedException) {
            assertSame(exhausted, expected)
        }
        // Non-vacuity: the failure above really attempted a transport read. Without
        // this a fully-cached page would pass the test while proving nothing.
        assertTrue(transport.reads > readsAfterOpen)
        val recovered = source.openPage(middlePage).readBytes()
        assertArrayEquals(randomPage(seeds[middlePage]), recovered)
        source.close()
        assertTrue(transport.closes >= 1)
    }

    @Test fun `credential expiry escapes unwrapped for sign-in-again`() {
        // Clause: typed errors surface as-is. Wrapping the expiry (even helpfully)
        // would hide it from the reader's `is` check, so identity is asserted.
        val expired = CredentialExpiredException("changed on the NAS", serverId = "nas")
        val (source, _) = openAndFailOnce(expired)
        try {
            source.openPage(middlePage).close()
            throw AssertionError("expected CredentialExpiredException")
        } catch (expected: CredentialExpiredException) {
            assertSame(expired, expected)
        }
        source.close()
    }

    @Test fun `an unreachable book fails the open, never the process`() {
        // Clause: never a crash. Establish failures are IOExceptions the reader's
        // generic error path already handles; only the container format itself returns
        // DownloadRequired, never a transport problem.
        val dead = object : RangeTransport {
            override fun sizeBytes(): Long = throw IOException("server down")
            override fun readAt(offset: Long, length: Int): ByteArray = throw IOException("server down")
        }
        val opener = TransportBookOpener { _, _ -> dead }
        runTest {
            try {
                opener.open("absolutex-remote://nas/comics/book.cbz")
                throw AssertionError("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("server down") == true)
            }
        }
    }
}
