package com.absolutex.remote.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI

// LAN servers may sleep and spin up slowly; connect 10s, read 30s.
const val CONNECT_TIMEOUT_MS = 10_000
const val READ_TIMEOUT_MS = 30_000

// HTTP vocabulary shared across modules. These were `internal` while HttpCall lived in
// :remote:sync and every caller was a sibling file; the move puts the callers in another
// module, so the ones crossing that boundary are public now. HTTP_BAD_REQUEST stays
// internal — only this file reads it.
const val HTTP_OK = 200
const val HTTP_PARTIAL = 206
const val HTTP_NO_CONTENT = 204
internal const val HTTP_BAD_REQUEST = 400
const val HTTP_UNAUTHORIZED = 401
const val HTTP_FORBIDDEN = 403
const val HTTP_NOT_FOUND = 404

const val AUTHORIZATION = "Authorization"
const val API_KEY_HEADER = "X-API-Key"
const val RANGE = "Range"

val JSON_HEADERS: Map<String, String> = mapOf("Content-Type" to "application/json")

const val DATE_HEADER = "Date"

/**
 * Ceiling on a buffered response body. [HttpCall.requestBytes] materialises the whole body
 * on the heap, so without a bound a server answering with something enormous — by fault or
 * by malice — is an OutOfMemoryError rather than an error we can report.
 *
 * 32 MiB, chosen from what actually flows through this call rather than from a round number:
 *
 * - The only `requestBytes` caller in the tree is Komga's page fetch
 *   (`/api/v1/books/{id}/pages/{n}`), so the largest legitimate body is **one comic page
 *   image**. This codebase already bounds exactly that artefact at 32 MiB —
 *   [CoreComicSource.MAX_ENTRY_BYTES], "a page is a few megabytes; larger means hostile
 *   input, not a scan" — and the three cover fetchers cap at 12 MiB. Matching the existing
 *   per-page bound keeps one number meaning one thing.
 * - JSON bodies go through `request`, not here, and both list endpoints are paginated by
 *   *us*: `KOMGA_PAGE_SIZE` is 20 and Kavita's `SERIES_PAGE_SIZE` is 100. A library of a
 *   few thousand series therefore never arrives in one response — it arrives in pages of
 *   20 or 100 — so the multi-megabyte listing this cap would otherwise have to clear
 *   cannot reach us through these clients at all. The text path is capped to the same
 *   value anyway, which leaves three orders of magnitude of headroom.
 *
 * Per-call override exists for callers that know better (a cover wants far less than a
 * page); this is only the default.
 */
const val DEFAULT_BODY_MAX_BYTES = 32L * 1024 * 1024

/**
 * A failed HTTP exchange with its status attached. An [IOException] so every existing
 * catch-and-queue path keeps compiling — but the runner matches on [code] first: 401/403
 * stop the server instead of retrying it (retried auth is an account lockout), and 404 on
 * a mapped book invalidates the mapping instead of pushing into the void.
 */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/**
 * Text response. [serverDateMs] is the server's `Date` header in epoch millis, or null when
 * the server sent none (or it did not parse): the clock-skew correction observes the server
 * clock through this field, never through the body.
 */
data class HttpResponse(
    val code: Int,
    val body: String,
    val serverDateMs: Long? = null,
    val headers: Map<String, List<String>> = emptyMap(),
)

class HttpBytesResponse(
    val code: Int,
    val bytes: ByteArray,
    val serverDateMs: Long? = null,
    val headers: Map<String, List<String>> = emptyMap(),
)

/**
 * A response whose body is **still on the wire**: status and headers have arrived, the bytes
 * have not. The caller owns it and must [close] it.
 *
 * This exists for ranged reads. A server that ignores `Range` answers 200 with the *entire*
 * file — Microsoft documents that fallback for Graph explicitly — and with [HttpCall.requestBytes]
 * the body is already on the heap by the time the status can be examined, which for a 300 MB
 * CBZ is an OutOfMemoryError. Here the status is readable before a single body byte is, so a
 * transport can see "200, not 206" and abandon the response having transferred nothing.
 *
 * [close] must therefore *abandon* the body, never drain it — see the implementation note in
 * [HttpUrlConnectionCall.requestStream].
 */
class HttpStreamResponse(
    val code: Int,
    val stream: InputStream,
    val headers: Map<String, List<String>> = emptyMap(),
    val serverDateMs: Long? = null,
    private val release: () -> Unit = {},
) : Closeable {
    /** Closes the body and releases the connection; [release] runs even if the stream throws. */
    override fun close() {
        try {
            stream.close()
        } finally {
            release()
        }
    }
}

interface HttpCall {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse

    /**
     * The whole body, on the heap, refusing anything past [maxBytes] rather than truncating
     * it — a silently short body would be parsed as a valid short one.
     */
    fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Long = DEFAULT_BODY_MAX_BYTES,
    ): HttpBytesResponse

    /**
     * Status and headers now, body on demand. The caller closes the result.
     *
     * Deliberately has no default implementation: every decorator of this interface has to
     * forward it, and a default would let one quietly not — which on
     * [CleartextHttpCall] means a streamed request escaping the cleartext policy, and on
     * sync's clock decorator means a server date silently unobserved. The compiler asking
     * each implementor to decide is the point.
     */
    fun requestStream(method: String, url: String, headers: Map<String, String>): HttpStreamResponse
}

/**
 * Reads at most [maxBytes], and fails rather than returning a truncated body. Reads one byte
 * past the cap to tell "exactly at the cap" from "there is more", so a body sitting exactly
 * on the limit is still served.
 */
internal fun InputStream.readCapped(maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "negative cap: $maxBytes" }
    val limit = (maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val bytes = readNBytes(limit)
    if (bytes.size.toLong() > maxBytes) {
        throw IOException("response body larger than $maxBytes bytes")
    }
    return bytes
}

/** Platform HttpURLConnection transport (java.net.http.HttpClient does not exist on Android). */
class HttpUrlConnectionCall(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : HttpCall {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val connection = openConnection(method, url, headers)
        try {
            if (body != null) connection.writeBody(body)
            return HttpResponse(
                connection.responseCode,
                connection.readBody(),
                connection.serverDate(),
                connection.responseHeaders(),
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpBytesResponse {
        val connection = openConnection(method, url, headers)
        try {
            return HttpBytesResponse(
                connection.responseCode,
                connection.readBytesBody(maxBytes),
                connection.serverDate(),
                connection.responseHeaders(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hands the caller the live body. The connection outlives this method — so, unlike every
     * other path here, it is *not* disconnected in a finally; the returned response owns it.
     *
     * The release closure calls `disconnect()` rather than relying on the stream's own close:
     * closing the stream alone invites HttpURLConnection to read the remainder of the body so
     * it can reuse the keep-alive connection, which is precisely the whole-file download this
     * seam exists to avoid. `disconnect()` drops the socket instead.
     */
    override fun requestStream(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): HttpStreamResponse {
        val connection = openConnection(method, url, headers)
        var handedOff = false
        try {
            val code = connection.responseCode
            val body = (if (code >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream)
                ?: InputStream.nullInputStream()
            val response = HttpStreamResponse(
                code,
                body,
                connection.responseHeaders(),
                connection.serverDate(),
            ) { connection.disconnect() }
            handedOff = true
            return response
        } finally {
            // Anything thrown before the caller took ownership leaves the socket ours to drop.
            if (!handedOff) connection.disconnect()
        }
    }

    private fun openConnection(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): java.net.HttpURLConnection {
        // URI.create keeps this warning-free on JDK 21, where URL(String) is deprecated.
        val connection = URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        // Redirects are followed manually by CleartextHttpCall, never here: an https URL
        // silently landing on http would bypass the per-server cleartext opt-in.
        connection.instanceFollowRedirects = false
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun java.net.HttpURLConnection.writeBody(body: String): Unit {
        doOutput = true
        outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
    }

    private fun java.net.HttpURLConnection.readBody(): String {
        // Error statuses still carry JSON bodies; a null stream just means empty.
        val stream = if (responseCode >= HTTP_BAD_REQUEST) errorStream else inputStream
        // Capped like the bytes path: a runaway text body is the same allocation.
        return stream?.use { String(it.readCapped(DEFAULT_BODY_MAX_BYTES), Charsets.UTF_8) } ?: ""
    }

    private fun java.net.HttpURLConnection.readBytesBody(maxBytes: Long): ByteArray {
        val stream = if (responseCode >= HTTP_BAD_REQUEST) errorStream else inputStream
        return stream?.use { it.readCapped(maxBytes) } ?: ByteArray(0)
    }

    /**
     * The server's clock as observed on this response. `getHeaderFieldDate` parses the
     * RFC-1123 `Date` every HTTP server sends; 0 (absent or unparseable) reads as unknown.
     */
    private fun java.net.HttpURLConnection.serverDate(): Long? =
        getHeaderFieldDate(DATE_HEADER, 0L).takeIf { it > 0L }

    /** Response headers without the null-keyed status line. */
    private fun java.net.HttpURLConnection.responseHeaders(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        headerFields.forEach { (name, values) ->
            if (name != null) out[name] = values
        }
        return out
    }
}
