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
 * would tie this file to it. A signed-in account supplies `CloudSession::withAccessToken`; these
 * tests supply a fixed header and never touch a token endpoint at all.
 */
fun interface AuthorizedRequest {
    fun request(call: (headers: Map<String, String>) -> HttpResponse): HttpResponse
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
        if (item.isFolder) throw IOException("not a file: $path")
        val url = item.downloadUrl ?: throw IOException("no download url for $path")
        return HttpRangeTransport(http, url, knownSizeBytes = item.sizeBytes, sleeper = sleeper)
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
