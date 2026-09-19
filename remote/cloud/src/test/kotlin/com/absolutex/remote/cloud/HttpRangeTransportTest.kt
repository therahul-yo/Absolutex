package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStatusException
import com.absolutex.remote.core.HttpStreamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class HttpRangeTransportTest {

    private val content = ByteArray(FILE_BYTES) { (it % 251).toByte() }

    private fun server(): FakeRangeHttp =
        FakeRangeHttp(FILE_BYTES.toLong()) { offset, length ->
            content.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

    private fun transportOver(
        http: HttpCall,
        size: Long? = FILE_BYTES.toLong(),
        headers: () -> Map<String, String> = ::emptyMap,
        sleeper: (Long) -> Unit = {},
    ) = HttpRangeTransport(http, "https://cloud.example/book.cbz", size, headers, sleeper)

    // --- honours Range ------------------------------------------------------------------

    @Test fun `a ranged read returns exactly those bytes`() {
        val fake = server()
        val bytes = transportOver(fake).readAt(100, 32)
        assertTrue(bytes.contentEquals(content.copyOfRange(100, 132)))
        assertEquals(listOf("bytes=100-131"), fake.rangesSeen)
    }

    @Test fun `a read pulls only the requested bytes off the wire`() {
        val fake = server()
        transportOver(fake).readAt(0, 64)
        assertEquals(64L, fake.bytesServed)
    }

    @Test fun `size is discovered from Content-Range when the caller has no metadata`() {
        val fake = server()
        assertEquals(FILE_BYTES.toLong(), transportOver(fake, size = null).sizeBytes())
        // The total comes out of the Content-Range header, so the probe never reads the body
        // it asked for: one round trip, zero bytes of file.
        assertEquals(listOf("bytes=0-0"), fake.rangesSeen)
        assertEquals(0L, fake.bytesServed)
        assertEquals(0, fake.openBodies)
    }

    @Test fun `a known size costs no round trip at all`() {
        val fake = server()
        assertEquals(FILE_BYTES.toLong(), transportOver(fake).sizeBytes())
        assertTrue(fake.rangesSeen.isEmpty())
    }

    // --- ignores Range ------------------------------------------------------------------

    @Test fun `a server that ignores Range fails without transferring the file`() {
        val fake = server()
        fake.mode = FakeRangeHttp.Mode.IGNORES_RANGE
        try {
            transportOver(fake).readAt(0, 16)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("ignored Range") == true)
        }
        // The whole point of the streaming seam: the 200 body is abandoned, not drained.
        assertEquals(0L, fake.bytesServed)
        assertEquals(0, fake.openBodies)
    }

    // --- breaks mid-stream --------------------------------------------------------------

    @Test fun `a body that breaks mid-stream surfaces as IOException, not a short page`() {
        val fake = server()
        fake.mode = FakeRangeHttp.Mode.BREAKS_MID_STREAM
        fake.breakAfterBytes = 8
        try {
            transportOver(fake).readAt(0, 64)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("reset mid-body") == true)
        }
        assertEquals(0, fake.openBodies)
    }

    // --- short reads --------------------------------------------------------------------

    @Test fun `a short answer is re-asked until the range is whole`() {
        val fake = server()
        fake.mode = FakeRangeHttp.Mode.SHORT_READ
        fake.shortReadBytes = 10
        val bytes = transportOver(fake).readAt(0, 30)
        assertTrue(bytes.contentEquals(content.copyOfRange(0, 30)))
        // Three answers of ten bytes, each a fresh range for the remainder.
        assertEquals(listOf("bytes=0-29", "bytes=10-29", "bytes=20-29"), fake.rangesSeen)
    }

    @Test fun `a server answering with nothing gives up instead of spinning`() {
        val fake = server()
        fake.mode = FakeRangeHttp.Mode.SHORT_READ
        fake.shortReadBytes = 0
        try {
            transportOver(fake).readAt(0, 30)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("short read") == true)
        }
        assertTrue("bounded, not a hot loop: ${fake.rangesSeen.size}", fake.rangesSeen.size <= 5)
    }

    // --- rate limits --------------------------------------------------------------------

    @Test fun `a rate limit is waited out for as long as Retry-After asks`() {
        val fake = server()
        fake.rateLimitsRemaining = 1
        fake.retryAfterHeader = "3"
        val slept = mutableListOf<Long>()
        val bytes = transportOver(fake, sleeper = { slept += it }).readAt(0, 16)
        assertTrue(bytes.contentEquals(content.copyOfRange(0, 16)))
        assertEquals(listOf(3_000L), slept)
    }

    @Test fun `a rate limit with no Retry-After still backs off rather than hot-looping`() {
        val fake = server()
        fake.rateLimitsRemaining = 1
        fake.retryAfterHeader = null
        val slept = mutableListOf<Long>()
        transportOver(fake, sleeper = { slept += it }).readAt(0, 16)
        assertEquals(1, slept.size)
        assertTrue("a zero backoff is a hot loop", slept.single() > 0)
    }

    @Test fun `an endless rate limit gives up after a bounded number of waits`() {
        val fake = server()
        fake.rateLimitsRemaining = Int.MAX_VALUE
        val slept = mutableListOf<Long>()
        try {
            transportOver(fake, sleeper = { slept += it }).readAt(0, 16)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("rate limited") == true)
        }
        assertTrue("bounded waits, got ${slept.size}", slept.size <= 4)
    }

    @Test fun `an absurd Retry-After is clamped instead of parking the reader for an hour`() {
        val fake = server()
        fake.rateLimitsRemaining = 1
        fake.retryAfterHeader = "86400"
        val slept = mutableListOf<Long>()
        transportOver(fake, sleeper = { slept += it }).readAt(0, 16)
        assertTrue("clamped, got ${slept.single()}", slept.single() <= 60_000L)
    }

    // --- revoked credentials ------------------------------------------------------------

    @Test fun `a rejected account asks for sign-in rather than looking like a network blip`() {
        val fake = server()
        fake.unauthorized = true
        try {
            transportOver(fake).readAt(0, 16)
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
        assertEquals(0, fake.openBodies)
    }

    @Test fun `an unexpected status keeps its code for the caller to match on`() {
        val http = object : StreamOnlyHttp() {
            override fun requestStream(m: String, u: String, h: Map<String, String>) =
                HttpStreamResponse(500, InputStream.nullInputStream())
        }
        try {
            transportOver(http).readAt(0, 16)
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(500, expected.code)
        }
    }

    // --- secrets ------------------------------------------------------------------------

    @Test fun `the auth header is re-read per request, so a refreshed token is picked up`() {
        val fake = server()
        var token = "first"
        val transport = transportOver(fake, headers = { mapOf("Authorization" to "Bearer $token") })
        transport.readAt(0, 8)
        token = "second"
        transport.readAt(8, 8)
        assertEquals("Bearer first", fake.headersSeen[0]["Authorization"])
        assertEquals("Bearer second", fake.headersSeen[1]["Authorization"])
    }

    @Test fun `no failure message carries the token or the url`() {
        val secret = "Bearer super-secret-token-value"
        val messages = mutableListOf<String>()
        for (mode in FakeRangeHttp.Mode.entries) {
            val fake = server()
            fake.mode = mode
            fake.breakAfterBytes = 1
            fake.shortReadBytes = 0
            runCatching {
                transportOver(fake, headers = { mapOf("Authorization" to secret) }).readAt(0, 32)
            }.exceptionOrNull()?.let { messages += "${it.message}" }
        }
        val fake = server()
        fake.unauthorized = true
        runCatching {
            transportOver(fake, headers = { mapOf("Authorization" to secret) }).readAt(0, 32)
        }.exceptionOrNull()?.let { messages += "${it.message}" }

        assertTrue("expected failures to assert on", messages.isNotEmpty())
        messages.forEach { message ->
            assertTrue("token leaked into: $message", !message.contains("super-secret"))
            assertTrue("token leaked into: $message", !message.contains("Bearer"))
            assertTrue("url leaked into: $message", !message.contains("cloud.example"))
        }
    }

    // --- cancellation -------------------------------------------------------------------

    @Test fun `no exit path leaves a body open`() {
        val fake = server()
        transportOver(fake).readAt(0, 32)
        assertEquals(0, fake.openBodies)
        assertTrue(fake.bodies.isNotEmpty())
    }

    @Test fun `closing mid-read drops the connection instead of waiting the body out`() {
        val started = CountDownLatch(1)
        val body = BlockingBody()
        val http = object : StreamOnlyHttp() {
            override fun requestStream(m: String, u: String, h: Map<String, String>): HttpStreamResponse {
                started.countDown()
                return HttpStreamResponse(206, body, mapOf("Content-Range" to listOf("bytes 0-31/$FILE_BYTES")))
            }
        }
        val transport = transportOver(http)
        val thrown = AtomicReference<Throwable?>()
        val reader = Thread { runCatching { transport.readAt(0, 32) }.exceptionOrNull()?.let(thrown::set) }
        reader.start()

        assertTrue("read never started", started.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue("body never blocked", body.blocking.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        transport.close()

        reader.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        assertTrue("the blocked read was never released", !reader.isAlive)
        assertTrue("close() did not close the in-flight body", body.closed)
        assertNotNull("the cancelled read should fail, not return", thrown.get())
    }

    @Test fun `every read after close fails instead of reaching the network`() {
        val fake = server()
        val transport = transportOver(fake)
        transport.readAt(0, 8)
        val before = fake.rangesSeen.size
        transport.close()
        try {
            transport.readAt(8, 8)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("closed") == true)
        }
        assertEquals(before, fake.rangesSeen.size)
    }

    @Test fun `close is idempotent`() {
        val fake = server()
        val transport = transportOver(fake)
        transport.close()
        transport.close()
    }

    /** A body that parks until it is closed, the way a socket read does. */
    private class BlockingBody : InputStream() {
        val blocking = CountDownLatch(1)
        private val released = CountDownLatch(1)

        @Volatile
        var closed = false
            private set

        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            blocking.countDown()
            released.await(AWAIT_SECONDS, TimeUnit.SECONDS)
            throw IOException("stream closed")
        }

        override fun close() {
            closed = true
            released.countDown()
        }
    }

    private abstract class StreamOnlyHttp : HttpCall {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse = throw UnsupportedOperationException()

        override fun requestBytes(
            method: String,
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpBytesResponse = throw UnsupportedOperationException()

        abstract override fun requestStream(
            m: String,
            u: String,
            h: Map<String, String>,
        ): HttpStreamResponse
    }

    private companion object {
        const val FILE_BYTES = 4096
        const val AWAIT_SECONDS = 5L
    }
}
