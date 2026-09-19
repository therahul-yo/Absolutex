package com.absolutex.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import java.io.InputStream
import org.junit.Test

/**
 * The streamed path carries the same cleartext policy as the buffered ones.
 *
 * Forwarding `requestStream` through [CleartextHttpCall] is a behaviour change, not plumbing:
 * without it a ranged read would reach the platform transport directly, so an `http://` server
 * or an https -> http redirect could put a bearer token on the wire in the clear. These assert
 * both halves — the scheme check and the cross-host header strip — plus the thing only the
 * streamed path can get wrong: an abandoned redirect hop holds its connection until someone
 * closes it.
 */
class CleartextStreamPolicyTest {

    /** Records whether the body was closed, so a leaked hop is visible. */
    private class RecordingStream : InputStream() {
        var closed = false
            private set

        override fun read(): Int = -1

        override fun close() {
            closed = true
        }
    }

    private class Stub(val code: Int, val location: String? = null)

    private class FakeCall(private vararg val stubs: Stub) : HttpCall {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val methods = mutableListOf<String>()
        val streams = mutableListOf<RecordingStream>()
        private var index = 0

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse = throw UnsupportedOperationException("stream-only fake")

        override fun requestBytes(
            method: String,
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpBytesResponse = throw UnsupportedOperationException("stream-only fake")

        override fun requestStream(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): HttpStreamResponse {
            methods += method
            urls += url
            this.headers += headers
            val stub = stubs.getOrElse(index) { Stub(HTTP_OK) }
            index++
            val stream = RecordingStream()
            streams += stream
            val head = stub.location?.let { mapOf("Location" to listOf(it)) } ?: emptyMap()
            return HttpStreamResponse(stub.code, stream, head)
        }
    }

    private val auth = mapOf(AUTHORIZATION to "Bearer secret-token", API_KEY_HEADER to "secret-key")

    @Test fun `a stream to http is refused when the server did not opt in`() {
        val fake = FakeCall()
        try {
            CleartextHttpCall(fake, allowCleartext = false)
                .requestStream("GET", "http://nas.local/a.cbz", emptyMap())
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("plain HTTP is not allowed") == true)
        }
        // Refused before anything reached the wire.
        assertTrue(fake.urls.isEmpty())
    }

    @Test fun `a stream to http is allowed once the server opts in`() {
        val fake = FakeCall()
        val response = CleartextHttpCall(fake, allowCleartext = true)
            .requestStream("GET", "http://nas.local/a.cbz", emptyMap())
        assertEquals(HTTP_OK, response.code)
        assertEquals(listOf("http://nas.local/a.cbz"), fake.urls)
    }

    @Test fun `an https stream is always allowed`() {
        val fake = FakeCall()
        val response = CleartextHttpCall(fake, allowCleartext = false)
            .requestStream("GET", "https://cloud.example/a.cbz", emptyMap())
        assertEquals(HTTP_OK, response.code)
    }

    @Test fun `a redirect that downgrades to http is refused, and the hop is closed`() {
        val fake = FakeCall(Stub(HTTP_MOVED_TEMP, "http://nas.local/a.cbz"))
        try {
            CleartextHttpCall(fake, allowCleartext = false)
                .requestStream("GET", "https://cloud.example/a.cbz", auth)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("plain HTTP is not allowed") == true)
        }
        // The refused hop must not be left holding its connection.
        assertTrue(fake.streams.single().closed)
    }

    @Test fun `a cross-host redirect strips Authorization and the API key`() {
        val fake = FakeCall(Stub(HTTP_MOVED_TEMP, "https://elsewhere.example/a.cbz"))
        CleartextHttpCall(fake, allowCleartext = false)
            .requestStream("GET", "https://cloud.example/a.cbz", auth)

        assertEquals(listOf("https://cloud.example/a.cbz", "https://elsewhere.example/a.cbz"), fake.urls)
        assertEquals("Bearer secret-token", fake.headers[0][AUTHORIZATION])
        assertNull(fake.headers[1][AUTHORIZATION])
        assertNull(fake.headers[1][API_KEY_HEADER])
    }

    @Test fun `a same-host redirect keeps the credentials`() {
        val fake = FakeCall(Stub(HTTP_MOVED_TEMP, "https://cloud.example/moved/a.cbz"))
        CleartextHttpCall(fake, allowCleartext = false)
            .requestStream("GET", "https://cloud.example/a.cbz", auth)
        assertEquals("Bearer secret-token", fake.headers[1][AUTHORIZATION])
        assertEquals("secret-key", fake.headers[1][API_KEY_HEADER])
    }

    @Test fun `every abandoned hop is closed, and only the returned one stays open`() {
        val fake = FakeCall(
            Stub(HTTP_MOVED_TEMP, "https://cloud.example/1"),
            Stub(HTTP_MOVED_TEMP, "https://cloud.example/2"),
        )
        val response = CleartextHttpCall(fake, allowCleartext = false)
            .requestStream("GET", "https://cloud.example/a.cbz", emptyMap())

        assertEquals(3, fake.streams.size)
        assertTrue(fake.streams[0].closed)
        assertTrue(fake.streams[1].closed)
        // The caller owns the last one; closing it here would hand back a dead stream.
        assertTrue(!fake.streams[2].closed)
        response.close()
        assertTrue(fake.streams[2].closed)
    }

    @Test fun `a redirect loop stops at the hop cap instead of spinning`() {
        val looping = Array(MAX_TEST_HOPS) { Stub(HTTP_MOVED_TEMP, "https://cloud.example/next") }
        val fake = FakeCall(*looping)
        CleartextHttpCall(fake, allowCleartext = false)
            .requestStream("GET", "https://cloud.example/a.cbz", emptyMap())
        // One initial request plus at most MAX_REDIRECTS hops, then it gives up.
        assertEquals(MAX_REDIRECTS_EXPECTED + 1, fake.urls.size)
    }

    @Test fun `closing releases the connection even when the body's close throws`() {
        var released = false
        val hostile = object : InputStream() {
            override fun read(): Int = -1
            override fun close() = throw IOException("body close failed")
        }
        val response = HttpStreamResponse(HTTP_OK, hostile) { released = true }
        try {
            response.close()
            fail("expected the body's IOException to propagate")
        } catch (expected: IOException) {
            assertEquals("body close failed", expected.message)
        }
        // The socket is released regardless — otherwise one hostile body leaks a connection.
        assertTrue(released)
    }

    private companion object {
        const val HTTP_MOVED_TEMP = 302
        const val MAX_REDIRECTS_EXPECTED = 5
        const val MAX_TEST_HOPS = 10
    }
}
