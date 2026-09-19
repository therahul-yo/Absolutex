package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStreamResponse
import com.absolutex.remote.core.RANGE
import java.io.IOException
import java.io.InputStream

/**
 * A ranged HTTP server with the failure modes M1 names: honours `Range`, ignores it, breaks
 * mid-stream, and answers short. Serves from a lazy byte source, so a 300 MB file costs
 * nothing to stand up.
 *
 * [bytesServed] counts bytes the client actually pulled off a body — the honest measure of
 * what crossed the wire, and the one the 300 MB proof rests on.
 */
internal class FakeRangeHttp(
    private val totalSize: Long,
    private val source: (Long, Int) -> ByteArray,
) : HttpCall {

    enum class Mode { PARTIAL, IGNORES_RANGE, BREAKS_MID_STREAM, SHORT_READ }

    var mode = Mode.PARTIAL
    var rateLimitsRemaining = 0
    var retryAfterHeader: String? = null
    var unauthorized = false

    /** Bytes handed to the client before a BREAKS_MID_STREAM body throws. */
    var breakAfterBytes = 1

    /** Bytes a SHORT_READ body serves before reporting EOF, however much was asked for. */
    var shortReadBytes = 1

    val rangesSeen = mutableListOf<String>()
    val headersSeen = mutableListOf<Map<String, String>>()
    val bodies = mutableListOf<ServedStream>()

    var bytesServed = 0L
        private set

    val openBodies: Int get() = bodies.count { !it.closed }

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse = throw UnsupportedOperationException("ranged fake serves streams only")

    override fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpBytesResponse = throw UnsupportedOperationException("ranged fake serves streams only")

    override fun requestStream(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): HttpStreamResponse {
        headersSeen += headers
        val range = headers[RANGE].orEmpty()
        rangesSeen += range
        return when {
            unauthorized -> bodiless(HTTP_UNAUTHORIZED)
            rateLimitsRemaining > 0 -> rateLimited()
            mode == Mode.IGNORES_RANGE -> wholeFile()
            else -> partial(range)
        }
    }

    private fun rateLimited(): HttpStreamResponse {
        rateLimitsRemaining--
        val head = retryAfterHeader?.let { mapOf("Retry-After" to listOf(it)) } ?: emptyMap()
        return HttpStreamResponse(HTTP_TOO_MANY_REQUESTS, InputStream.nullInputStream(), head)
    }

    /** 200 plus the entire file — the fallback Microsoft documents for Graph. */
    private fun wholeFile(): HttpStreamResponse {
        val body = ServedStream(0, totalSize.toInt(), -1)
        bodies += body
        return HttpStreamResponse(HTTP_OK, body, mapOf("Content-Length" to listOf(totalSize.toString())))
    }

    private fun partial(range: String): HttpStreamResponse {
        val (start, endInclusive) = parseRange(range)
        val asked = (endInclusive - start + 1).toInt()
        val serve = when (mode) {
            Mode.SHORT_READ -> minOf(asked, shortReadBytes)
            else -> asked
        }
        val body = ServedStream(start, serve, if (mode == Mode.BREAKS_MID_STREAM) breakAfterBytes else -1)
        bodies += body
        val head = mapOf(
            "Content-Range" to listOf("bytes $start-$endInclusive/$totalSize"),
            "Accept-Ranges" to listOf("bytes"),
        )
        return HttpStreamResponse(HTTP_PARTIAL, body, head)
    }

    private fun bodiless(code: Int): HttpStreamResponse =
        HttpStreamResponse(code, InputStream.nullInputStream())

    private fun parseRange(range: String): Pair<Long, Long> {
        val spec = range.removePrefix("bytes=")
        val start = spec.substringBefore('-').toLong()
        val end = spec.substringAfter('-').toLong()
        return start to minOf(end, totalSize - 1)
    }

    /** A body that counts what is read and reports whether it was closed. */
    internal inner class ServedStream(
        private var offset: Long,
        private var remaining: Int,
        private val breakAfter: Int,
    ) : InputStream() {
        private var served = 0

        var closed = false
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (breakAfter in 0..served) throw IOException("connection reset mid-body")
            if (remaining <= 0) return -1
            // Chunked like a real socket: one read never hands over the whole range.
            var take = minOf(len, remaining, CHUNK)
            if (breakAfter >= 0) take = minOf(take, breakAfter - served)
            if (take <= 0) throw IOException("connection reset mid-body")
            source(offset, take).copyInto(buffer, off)
            offset += take
            remaining -= take
            served += take
            bytesServed += take.toLong()
            return take
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val CHUNK = 64 * 1024
    }
}
