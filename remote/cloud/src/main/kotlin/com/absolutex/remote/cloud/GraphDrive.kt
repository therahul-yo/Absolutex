package com.absolutex.remote.cloud

import com.absolutex.remote.core.HTTP_OK
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStatusException
import java.io.IOException
import java.net.URI

/**
 * One entry in a OneDrive folder.
 *
 * [downloadUrl] is **pre-authenticated**: Microsoft Graph answers item metadata with a URL that
 * already carries its own credential and needs no `Authorization` header. That makes it a secret
 * living in a URI, which is exactly the shape this project otherwise forbids — it cannot be
 * changed here, so it is bounded instead. It is never logged, never put in an error message,
 * and never used as an identity or a cache key: [id] is the stable name for this file, and the
 * URL is short-lived besides.
 */
data class DriveItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val isFolder: Boolean,
    val downloadUrl: String?,
)

/** A page of children, plus Graph's `@odata.nextLink` when more remain. */
data class DrivePage(val items: List<DriveItem>, val nextLink: String?)

/**
 * Reads Graph's JSON. Injected for the reason established in [TokenParser]: `:remote:cloud`
 * stays plain Kotlin/JVM and JSON-free, and the Android provider module parses with the
 * platform parser under Robolectric.
 */
interface GraphParser {
    /** A `children` response: its items and the link to the next page, if any. */
    fun page(body: String): DrivePage

    /** A single `driveItem` response. */
    fun item(body: String): DriveItem

    /** Graph's `error.code`, or null when the body carries none. */
    fun errorCode(body: String): String?
}

/**
 * Runs a request with an account's authorization, refreshing and retrying once if it is refused.
 *
 * Injected rather than taken as a concrete session for the same reason the JSON parse is: it is
 * the only thing [GraphDrive] needs from an account, and depending on the whole token lifecycle
 * would tie this file to it. A signed-in account supplies [CloudSession.asAuthorizedRequest] —
 * **not** `CloudSession::withAccessToken` directly, which compiles but never refreshes on a 401
 * response; see the adapter for why. These tests supply a fixed header and never touch a token
 * endpoint; `ProviderRefreshTest` is where the two are composed for real.
 */
fun interface AuthorizedRequest {
    fun request(call: (headers: Map<String, String>) -> HttpResponse): HttpResponse
}

/**
 * A Graph download URL that re-resolves itself before it can go stale.
 *
 * [DriveItem.downloadUrl] is pre-authenticated and short-lived, so a transport holding one for
 * the length of a big read would eventually present a dead URL — and because the refusal is a
 * 401/403, that surfaces as "sign in again" when the account was never the problem. This caches
 * one URL and re-resolves on a timer, so [HttpRangeTransport] can keep asking without ever
 * knowing that URLs expire.
 *
 * **Cached, because the alternative is absurd.** The transport asks per *request*; a supplier
 * that resolved every time would turn one ranged read into a Graph round trip per block — about
 * 300 extra calls for a 300 MB book, which is the very cost streaming exists to avoid.
 *
 * **Single-flight, because reads fan out.** `readAt` is explicitly concurrent, so an expired URL
 * expires for every in-flight read at once. Resolving under the lock means the losers wait for
 * the winner's URL and find it already fresh, rather than each firing their own metadata call.
 *
 * **On the TTL.** Graph does not state the lifetime in the response, so this cannot be derived
 * and is deliberately set far below any documented value. The asymmetry decides it: too long
 * costs a user-visible, wrong sign-in prompt, while too short costs one cheap metadata GET —
 * about thirty an hour, against the hundreds of ranged reads happening anyway.
 *
 * **A URL that dies before its TTL is caught too, because of what this URL is.** Revocation, or
 * Graph simply shortening the lifetime, refuses a URL the timer still believes in. The transport
 * asks again naming the URL it was refused on, and that is enough here: this URL is fetched with
 * **no** `Authorization` header at all, so a 401/403 on it cannot mean the account was refused —
 * there is no account credential on the request to refuse. On the pre-authenticated path a
 * refusal means a dead URL and nothing else.
 *
 * **The same lock makes that single-flight for free.** A refusal re-resolves only when the named
 * URL is still the one held, so N concurrent reads all refused on the same URL cause one metadata
 * call: the winner replaces it, and every loser names a URL that is no longer current and is
 * handed the winner's. That is the compare-and-swap in `CloudSession`'s refresh generation, on a
 * string instead of a counter.
 */
internal class CachedDownloadUrl(
    initial: String,
    private val ttlMillis: Long,
    private val clock: () -> Long,
    private val resolve: () -> String,
) {
    private val guard = Any()
    private var url: String = initial
    private var freshUntil: Long = clock() + ttlMillis

    /**
     * The URL to use now, re-resolved when the TTL has passed or when [refused] is still the one
     * held. [refused] is null for an ordinary ask and carries the URL the server just rejected
     * otherwise — naming it, rather than an argument-less `invalidate()`, is what keeps a late
     * loser from throwing away the replacement a winner has already fetched.
     */
    fun get(refused: String? = null): String = synchronized(guard) {
        if (clock() >= freshUntil || refused == url) {
            url = resolve()
            freshUntil = clock() + ttlMillis
        }
        url
    }
}

/**
 * OneDrive over Microsoft Graph, by raw REST — no provider SDK, per the lane's constraint.
 *
 * **Why metadata first and not `/content` directly.** Graph's `/items/{id}/content` answers
 * **302** with a redirect to storage, and [com.absolutex.remote.core.HttpUrlConnectionCall] sets
 * `instanceFollowRedirects = false` on purpose, so an https URL cannot silently land on http and
 * bypass the per-server cleartext opt-in. A ranged read of `/content` would therefore see a 302,
 * not a 206, and fail. The supported path is to read [DriveItem.downloadUrl] from metadata and
 * range-read that, with **no** `Authorization` header — it is already authenticated, and
 * attaching a bearer token to a third-party storage host would hand the token away.
 *
 * **Following `@odata.nextLink` is a trust decision, not just a loop.** That link arrives in a
 * server response and this client sends a bearer token to whatever it names, so a hostile or
 * compromised body could exfiltrate the access token to any host it liked simply by pointing
 * the next page elsewhere. Every link is therefore checked to be the same https origin as the
 * configured base before a token goes anywhere near it.
 */
class GraphDrive(
    private val http: HttpCall,
    private val authorized: AuthorizedRequest,
    private val parser: GraphParser,
    private val baseUrl: String = GRAPH_BASE,
    private val urlTtlMillis: Long = DOWNLOAD_URL_TTL_MS,
    private val clock: () -> Long = ::monotonicMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {

    /** Everything in a folder, following pagination. Empty path means the drive root. */
    @Throws(IOException::class)
    fun listFolder(path: String): List<DriveItem> {
        val items = mutableListOf<DriveItem>()
        var url: String? = childrenUrl(path)
        var pages = 0
        while (url != null) {
            pages++
            if (pages > MAX_PAGES) throw IOException("folder paged past $MAX_PAGES pages")
            val page = parser.page(getJson(url))
            items += page.items
            url = page.nextLink?.also(::requireSameOrigin)
        }
        return items
    }

    /** The item at [path], whether file or folder. */
    @Throws(IOException::class)
    fun itemAt(path: String): DriveItem = parser.item(getJson(itemUrl(path)))

    /**
     * A ranged transport over the file at [path].
     *
     * No auth headers: [DriveItem.downloadUrl] is pre-authenticated, and sending the account's
     * bearer token to the storage host it points at would be giving the token to a third party.
     */
    @Throws(IOException::class)
    fun open(path: String): HttpRangeTransport {
        val item = itemAt(path)
        val cached = CachedDownloadUrl(downloadUrlOf(item, path), urlTtlMillis, clock) {
            downloadUrlOf(itemAt(path), path)
        }
        return HttpRangeTransport(http, cached::get, knownSizeBytes = item.sizeBytes, sleeper = sleeper)
    }

    /**
     * An item's pre-authenticated download URL, or a clear refusal.
     *
     * Shared between the first resolution and every re-resolution on purpose: a path that has
     * since become a folder, or lost its URL, must be refused the same way the second time as
     * the first. Re-resolution is not a trusted path just because the first one succeeded.
     */
    private fun downloadUrlOf(item: DriveItem, path: String): String {
        if (item.isFolder) throw IOException("not a file: $path")
        return item.downloadUrl ?: throw IOException("no download url for $path")
    }

    /** A GET carrying the account's token, retried only for throttling and only as asked. */
    private fun getJson(url: String): String {
        var attempt = 0
        while (true) {
            val response = authorized.request { headers -> http.request(GET, url, headers, null) }
            if (response.code == HTTP_OK) return response.body
            val retryMs = throttleDelayOrNull(response.code, response.headers)
            if (retryMs == null) throw failureFor(response.code, response.body)
            attempt++
            if (attempt > MAX_THROTTLE_RETRIES) throw IOException("graph throttled after $attempt attempts")
            backoff(retryMs)
        }
    }

    /** Status to error. Never names the url — a Graph url can carry a pre-authenticated token. */
    private fun failureFor(code: Int, body: String): IOException {
        val reason = runCatching { parser.errorCode(body) }.getOrNull()
        return when {
            reason == ITEM_NOT_FOUND || code == NOT_FOUND -> IOException("not found on this drive")
            else -> HttpStatusException(code, "graph request failed with $code")
        }
    }

    private fun throttleDelayOrNull(code: Int, headers: Map<String, List<String>>): Long? =
        if (code == TOO_MANY_REQUESTS || code == UNAVAILABLE) retryAfterMillis(headers) else null

    private fun backoff(delayMs: Long) {
        try {
            sleeper(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled while waiting out graph throttling", interrupted)
        }
    }

    /**
     * Refuses a link that would send this account's token somewhere else.
     *
     * The message names the host and never the full link: a rejected link is still attacker-
     * chosen text, and the query of a legitimate one can carry a skip token.
     */
    private fun requireSameOrigin(link: String) {
        val base = URI.create(baseUrl)
        val next = runCatching { URI.create(link) }.getOrNull()
            ?: throw IOException("next page link is not a url")
        val same = next.scheme.equals(HTTPS, ignoreCase = true) &&
            next.host.equals(base.host, ignoreCase = true) &&
            next.port == base.port
        if (!same) throw IOException("next page link left the api host: ${next.host}")
    }

    private fun childrenUrl(path: String): String =
        if (path.isBlank() || path == "/") "$baseUrl$ROOT/children" else "$baseUrl$ROOT:${encodePath(path)}:/children"

    private fun itemUrl(path: String): String =
        if (path.isBlank() || path == "/") "$baseUrl$ROOT" else "$baseUrl$ROOT:${encodePath(path)}"

    private companion object {
        const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        const val ROOT = "/me/drive/root"
        const val GET = "GET"
        const val HTTPS = "https"
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
        const val UNAVAILABLE = 503
        const val ITEM_NOT_FOUND = "itemNotFound"
        const val MAX_THROTTLE_RETRIES = 3

        /**
         * Two minutes. Not derived — Graph does not report the download URL's lifetime — so it
         * is pitched far under any documented figure, because the two failure directions are
         * not symmetric: too long shows the user a wrong sign-in prompt, too short costs one
         * metadata GET.
         */
        const val DOWNLOAD_URL_TTL_MS = 2 * 60 * 1000L

        /**
         * Graph pages children 200 at a time, so this allows a 100,000-file library before
         * giving up. The bound exists because `@odata.nextLink` is server-controlled: without
         * it a link that always points to another page is an unbounded loop, not a big folder.
         */
        const val MAX_PAGES = 500

        /** Percent-encodes each segment, leaving `/` as the separator Graph expects. */
        fun encodePath(path: String): String = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }
}

/**
 * Milliseconds from a monotonic source, for timing a download URL out.
 *
 * **Not the wall clock.** `System.currentTimeMillis` can jump *backwards* — an NTP correction, a
 * user setting the date, a device with no RTC catching up after boot — and a backwards jump
 * extends freshness, which is the one direction a TTL cannot survive: the URL stays "fresh"
 * exactly while it is dying. Forwards is harmless here, because an early re-resolution costs one
 * cheap metadata GET.
 *
 * **Android callers must inject `SystemClock::elapsedRealtime` instead.** `System.nanoTime` is
 * monotonic but does not advance across deep sleep, so a book left open on a sleeping device sees
 * far less time pass than really did. Since [CachedDownloadUrl] now also re-resolves on a refusal,
 * that costs one refused request rather than a wrong sign-in prompt — but the clock that counts
 * sleep is still the right one, and `:remote:cloud` is plain Kotlin/JVM and cannot name it here.
 * This is the wiring M6 has to get right; the parameter exists so it can.
 */
internal fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
