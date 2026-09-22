package com.absolutex.remote.cloud

import com.absolutex.remote.core.HTTP_FORBIDDEN
import com.absolutex.remote.core.HTTP_OK
import com.absolutex.remote.core.HTTP_PARTIAL
import com.absolutex.remote.core.HTTP_UNAUTHORIZED
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpStatusException
import com.absolutex.remote.core.HttpStreamResponse
import com.absolutex.remote.core.RANGE
import com.absolutex.remote.core.RangeTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The account's credentials are no longer accepted and no refresh can rescue them — a revoked
 * or withdrawn grant. Distinct from a transient failure on purpose: this one has exactly one
 * remedy, which is the user signing in again, so the UI can say so instead of offering a retry
 * that cannot work.
 */
class ReauthRequiredException(message: String) : IOException(message)

/**
 * [RangeTransport] over one HTTP file, reading byte ranges through [HttpCall.requestStream].
 *
 * This is the whole cloud strategy in one class: a provider whose download endpoint honours
 * `Range` can be read exactly like an SMB or FTP file — central directory first, then only the
 * pages actually turned — so a 300 MB CBZ opens without transferring 300 MB.
 *
 * **Why the streaming seam and not `requestBytes`.** A server that ignores `Range` answers 200
 * with the *entire* file; Microsoft documents that fallback for Graph explicitly. A buffered
 * call would have those bytes on the heap before the status could be examined, so noticing the
 * 200 afterwards would be too late — the allocation already happened. Here the status arrives
 * first and [abandonedUnlessPartial] drops the connection having transferred nothing.
 *
 * **Threading.** Offset-based like pread: concurrent [readAt] calls are independent, each with
 * its own request and its own response, which is what lets the decode pool fan pages out. No
 * shared stream position exists to corrupt.
 *
 * **Cancellation.** [close] latches and closes every in-flight response, so a read blocked on a
 * slow body is dropped rather than waited out, and every later read fails instead of running.
 *
 * **Secrets.** Auth arrives from [headers], read per request so a refreshed token is picked up
 * without rebuilding the transport. Nothing here logs, no header value is ever put in a message,
 * and no token is ever placed in a message — those carry the status and the range, never the
 * credential and never the URL.
 *
 * **[url] is a supplier for the same reason [headers] is.** Some providers hand out a
 * short-lived, pre-authenticated download URL rather than a stable one — Microsoft Graph's
 * `@microsoft.graph.downloadUrl` is the case that forced this — and a transport that resolved
 * once at construction would keep presenting a dead URL for the rest of a long read. Since the
 * refusal arrives as 401/403, that would surface as [ReauthRequiredException] and tell the user
 * to sign in again when their token was never the problem.
 *
 * Asking for a URL per request inverts that: this class stops knowing that URLs expire at all,
 * and whoever supplies them owns the policy. "Resolve once at construction" was never true here
 * anyway — one transport makes many requests — so the supplier only makes the existing shape
 * honest. A stable URL costs `{ url }` at the call site.
 */
class HttpRangeTransport(
    private val http: HttpCall,
    private val url: () -> String,
    knownSizeBytes: Long? = null,
    private val headers: () -> Map<String, String> = ::emptyMap,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : RangeTransport {

    private val guard = Any()
    private val inFlight = LinkedHashSet<HttpStreamResponse>()
    private var discoveredSize: Long? = knownSizeBytes

    @Volatile
    private var closed = false

    // Atomic, not plain fields: readAt is explicitly concurrent, so `+= n` would be a
    // read-modify-write race — and these two counters are the evidence the 300 MB claim
    // rests on, which makes them the last numbers that should be approximately right.
    private val fetched = AtomicLong(0L)
    private val calls = AtomicInteger(0)

    /** Bytes actually pulled from the wire — the proof that a big book stays mostly unread. */
    val bytesFetched: Long get() = fetched.get()

    /** Ranged requests issued. Round trips are observable here, not on a stopwatch. */
    val readCalls: Int get() = calls.get()

    override fun sizeBytes(): Long {
        synchronized(guard) { discoveredSize?.let { return it } }
        // One ranged byte answers both questions at once: the total from `Content-Range`, and
        // whether this endpoint honours Range at all — a 200 here fails before a book opens
        // rather than part-way through reading one.
        val total = openRange(0, 1).use { response ->
            totalFromContentRange(response.headers)
                ?: throw IOException("ranged response carried no Content-Range total")
        }
        synchronized(guard) {
            discoveredSize = total
            return total
        }
    }

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var done = 0
        var barren = 0
        while (done < length) {
            ensureOpen()
            val got = fillOnce(offset + done, length - done, out, done)
            if (got > 0) {
                barren = 0
            } else {
                // A server that keeps answering a range with nothing is broken, not slow.
                barren++
                if (barren > MAX_EMPTY_ANSWERS) {
                    throw IOException("short read: $done of $length bytes at $offset")
                }
            }
            done += got
        }
        return out
    }

    /**
     * Latches, then drops every response still on the wire. Without this a read blocked on a
     * stalled body would hold the book open until the socket timed out on its own.
     */
    override fun close() {
        val open: List<HttpStreamResponse>
        synchronized(guard) {
            if (closed) return
            closed = true
            open = inFlight.toList()
            inFlight.clear()
        }
        // Outside the lock, and contained: one failing close must not hide the others.
        open.forEach { runCatching { it.close() } }
    }

    /**
     * One ranged request, copying whatever it yields into [out]. Returns the count so the
     * caller can re-ask for the remainder: a server is allowed to answer a range with fewer
     * bytes than asked, and treating that as the whole answer would silently corrupt a page.
     */
    private fun fillOnce(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
        val response = openRange(offset, length)
        try {
            var filled = 0
            while (filled < length) {
                val read = response.stream.read(out, outOffset + filled, length - filled)
                if (read < 0) break
                filled += read
            }
            fetched.addAndGet(filled.toLong())
            return filled
        } finally {
            synchronized(guard) { inFlight.remove(response) }
            response.close()
        }
    }

    /**
     * A 206 for `[offset, offset+length)`, retrying only a rate limit and only for as long as
     * the server asks. Every other non-206 closes here and throws — the caller never receives
     * a response it might read by mistake.
     */
    private fun openRange(offset: Long, length: Int): HttpStreamResponse {
        val range = "bytes=$offset-${offset + length - 1}"
        var attempt = 0
        while (true) {
            ensureOpen()
            calls.incrementAndGet()
            // Resolved per request, not per transport: a supplier that re-resolves an expired
            // URL takes effect on the next range without the book being reopened.
            val response = http.requestStream(GET, url(), headers() + (RANGE to range))
            val retryMs = rateLimitDelayOrNull(response)
            if (retryMs == null) {
                return abandonedUnlessPartial(response, range)
            }
            response.close()
            attempt++
            if (attempt > MAX_RATE_LIMIT_RETRIES) {
                throw IOException("rate limited on $range after $attempt attempts")
            }
            backoff(retryMs)
        }
    }

    /**
     * Returns the response only for 206. Everything else is closed first, so the 200 case —
     * a server ignoring Range and sending the whole file — costs one set of headers rather
     * than the whole book.
     */
    private fun abandonedUnlessPartial(response: HttpStreamResponse, range: String): HttpStreamResponse {
        if (response.code == HTTP_PARTIAL) {
            synchronized(guard) {
                if (closed) {
                    response.close()
                    throw IOException("remote file closed")
                }
                inFlight.add(response)
            }
            return response
        }
        response.close()
        throw failureFor(response.code, range)
    }

    /** Status to error. Nothing here names the URL or a header — a message is not a place for a token. */
    private fun failureFor(code: Int, range: String): IOException = when (code) {
        HTTP_OK -> IOException(
            "server ignored Range and answered 200 with the whole file — this endpoint cannot stream",
        )
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
            ReauthRequiredException("sign-in required: the server rejected this account")
        HTTP_RANGE_NOT_SATISFIABLE -> IOException("range not satisfiable: $range")
        else -> HttpStatusException(code, "ranged read failed with $code")
    }

    private fun rateLimitDelayOrNull(response: HttpStreamResponse): Long? {
        if (response.code != HTTP_TOO_MANY_REQUESTS && response.code != HTTP_UNAVAILABLE) return null
        return retryAfterMillis(response.headers)
    }

    /** Sleeps, but stays cancellable: an interrupt ends the read rather than being swallowed. */
    private fun backoff(delayMs: Long) {
        try {
            sleeper(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled while waiting out a rate limit", interrupted)
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("remote file closed")
    }

    private companion object {
        const val GET = "GET"
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503

        /** Enough to ride out a burst limit; beyond this the account is over quota, not busy. */
        const val MAX_RATE_LIMIT_RETRIES = 3

        /** A server answering a range with zero bytes this many times is broken, not slow. */
        const val MAX_EMPTY_ANSWERS = 3
    }
}

/**
 * `Retry-After` in millis, clamped. Absent or unparseable reads as the default rather than as
 * zero: a rate limit answered instantly is a hot loop, which is how an account gets suspended
 * rather than throttled. The delta-seconds form is honoured; the HTTP-date form falls back to
 * the default, since a date needs a trustworthy clock and the one thing a throttled server has
 * just told us is that we are asking too fast.
 */
internal fun retryAfterMillis(headers: Map<String, List<String>>): Long {
    val raw = headers.entries
        .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?.trim()
    val seconds = raw?.toLongOrNull() ?: return DEFAULT_RETRY_AFTER_MS
    return (seconds * MILLIS_PER_SECOND).coerceIn(0L, MAX_RETRY_AFTER_MS)
}

/** The total size out of `Content-Range: bytes 0-0/12345`, or null when it is absent or a `*`. */
internal fun totalFromContentRange(headers: Map<String, List<String>>): Long? {
    val raw = headers.entries
        .firstOrNull { it.key.equals("content-range", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?: return null
    return raw.substringAfterLast('/', "").trim().toLongOrNull()
}

private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_RETRY_AFTER_MS = 2_000L
private const val MAX_RETRY_AFTER_MS = 60_000L
