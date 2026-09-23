package com.absolutex.remote.cloud

import com.absolutex.remote.core.HTTP_OK
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpStatusException
import java.io.IOException

/**
 * One entry in a Dropbox folder.
 *
 * [pathLower] is Dropbox's own case-normalised path and is what every later request addresses
 * this file by. [id] is the stable identity that survives a rename or a move; a path does not.
 */
data class DropboxEntry(
    val id: String,
    val name: String,
    val pathLower: String,
    val sizeBytes: Long,
    val isFolder: Boolean,
)

/** A page of entries, plus the cursor to continue with when more remain. */
data class DropboxPage(val entries: List<DropboxEntry>, val cursor: String?)

/**
 * Reads and *writes* Dropbox's JSON. Injected for the reason established in [TokenParser] and
 * [GraphParser]: `:remote:cloud` stays plain Kotlin/JVM and JSON-free, and the Android provider
 * module uses the platform parser under Robolectric.
 *
 * **Writing is in this seam too, which the Graph one did not need.** Graph addresses items in the
 * URL path; Dropbox sends the path as a JSON *request body*. A path is a user's file name, so it
 * can legally contain a quote or a backslash — `He said "hi".cbz` is a valid name — and a
 * hand-rolled escaper is the same class of bug the parse seam exists to avoid, with the failure
 * landing on exactly the files whose names are already awkward. `JSONObject` escapes correctly;
 * this module will not try to.
 */
interface DropboxJson {
    /** A request body carrying a single `path`, correctly escaped. */
    fun pathBody(path: String): String

    /** A request body carrying a single `cursor`. */
    fun cursorBody(cursor: String): String

    /** A `list_folder` response: its entries, and the cursor if `has_more` was set. */
    fun page(body: String): DropboxPage

    /** A single metadata response. */
    fun entry(body: String): DropboxEntry

    /** The `link` from a `get_temporary_link` response. */
    fun temporaryLink(body: String): String

    /** Dropbox's `error_summary`, or null when the body carries none. */
    fun errorSummary(body: String): String?
}

/**
 * Dropbox files over the documented v2 HTTP API, by raw REST — no provider SDK, per the lane's
 * constraint. OAuth is not repeated here: [CloudTokenEndpoint] is already provider-agnostic and
 * Dropbox's PKCE flow is the same RFC 6749 form grant, so it needs configuration, not code.
 *
 * **Why `get_temporary_link` and not `/2/files/download`.** The download endpoint is a POST that
 * carries its argument in a `Dropbox-API-Arg` *header*, which must be ASCII-only JSON — a second
 * escaping rule, applied to a user's file name, in a header. `get_temporary_link` instead returns
 * a plain GET-able URL that honours `Range`, so the ranged read goes through [HttpRangeTransport]
 * unchanged and no file name ever has to survive header encoding.
 *
 * **That link is the same shape as Graph's `@microsoft.graph.downloadUrl`**: pre-authenticated,
 * short-lived, and to be fetched with **no** `Authorization` header — attaching the account's
 * bearer token to a storage host would hand the token to a third party. So [CachedDownloadUrl]
 * applies verbatim, including the refusal path: a link that dies early is refused, and since the
 * request carries no account credential, that refusal can only mean the link is dead.
 *
 * **The TTL here is derived, unlike Graph's.** Dropbox documents the temporary link's lifetime as
 * four hours. This still pitches well under it rather than sitting on the boundary, because the
 * two failure directions are not symmetric and the document states a maximum, not a guarantee —
 * but this is a figure read off a spec, not the asymmetry argument [GraphDrive] had to fall back
 * on.
 *
 * **Paging by cursor removes a trust decision rather than making one.** Graph returns an
 * `@odata.nextLink` — a server-supplied URL this client would then send a bearer token to, which
 * is why [GraphDrive] pins its origin. Dropbox returns an opaque *cursor* and the client builds
 * the continue URL itself, so no server-controlled string ever decides where the token goes.
 * The bound below is still needed: a cursor that always reports more is an unbounded loop.
 */
class DropboxFiles(
    private val http: HttpCall,
    private val authorized: AuthorizedRequest,
    private val json: DropboxJson,
    private val apiBaseUrl: String = API_BASE,
    private val linkTtlMillis: Long = TEMPORARY_LINK_TTL_MS,
    private val clock: () -> Long = ::monotonicMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {

    /** Everything in a folder, following the cursor. Empty path means the account root. */
    @Throws(IOException::class)
    fun listFolder(path: String): List<DropboxEntry> {
        val entries = mutableListOf<DropboxEntry>()
        var body = json.pathBody(rootAware(path))
        var url = "$apiBaseUrl$LIST_FOLDER"
        var pages = 0
        while (true) {
            pages++
            if (pages > MAX_PAGES) throw IOException("folder paged past $MAX_PAGES pages")
            val page = json.page(postJson(url, body))
            entries += page.entries
            val cursor = page.cursor ?: return entries
            body = json.cursorBody(cursor)
            url = "$apiBaseUrl$LIST_FOLDER_CONTINUE"
        }
    }

    /** The entry at [path], whether file or folder. */
    @Throws(IOException::class)
    fun entryAt(path: String): DropboxEntry =
        json.entry(postJson("$apiBaseUrl$GET_METADATA", json.pathBody(rootAware(path))))

    /**
     * A ranged transport over the file at [path].
     *
     * No auth headers on the ranged reads: the temporary link is pre-authenticated, and sending
     * the account's bearer token to the storage host it points at would be giving it away.
     */
    @Throws(IOException::class)
    fun open(path: String): HttpRangeTransport {
        val entry = entryAt(path)
        if (entry.isFolder) throw IOException("not a file: $path")
        // Both the first resolution and every re-resolution go through the same call, so a path
        // that has since become a folder, or been deleted, is refused identically the second
        // time. A re-resolution is not a trusted path just because the first one succeeded.
        val cached = CachedDownloadUrl(temporaryLinkFor(path), linkTtlMillis, clock) {
            temporaryLinkFor(path)
        }
        return HttpRangeTransport(http, cached::get, knownSizeBytes = entry.sizeBytes, sleeper = sleeper)
    }

    private fun temporaryLinkFor(path: String): String =
        json.temporaryLink(postJson("$apiBaseUrl$GET_TEMPORARY_LINK", json.pathBody(rootAware(path))))

    /** Dropbox names the account root as the empty string, not as `/`. */
    private fun rootAware(path: String): String = if (path.isBlank() || path == "/") "" else path

    /** A POST carrying the account's token, retried only for throttling and only as asked. */
    private fun postJson(url: String, body: String): String {
        var attempt = 0
        while (true) {
            val response = authorized.request { headers -> http.request(POST, url, headers + JSON_TYPE, body) }
            if (response.code == HTTP_OK) return response.body
            val retryMs = throttleDelayOrNull(response.code, response.headers)
            if (retryMs == null) throw failureFor(response.code, response.body)
            attempt++
            if (attempt > MAX_THROTTLE_RETRIES) throw IOException("dropbox throttled after $attempt attempts")
            backoff(retryMs)
        }
    }

    /**
     * Status to error. Never names the url or the path: a temporary link carries its own
     * credential, and a path is the user's private file name.
     *
     * Dropbox answers endpoint-specific failures with **409**, not 404, and puts the reason in
     * `error_summary` — so a missing file is a 409 whose summary starts `path/not_found`, and
     * treating 409 as a generic failure would report "conflict" for a file that simply is not
     * there.
     */
    private fun failureFor(code: Int, body: String): IOException {
        val summary = runCatching { json.errorSummary(body) }.getOrNull().orEmpty()
        return when {
            summary.startsWith(PATH_NOT_FOUND) -> IOException("not found on this account")
            else -> HttpStatusException(code, "dropbox request failed with $code")
        }
    }

    private fun throttleDelayOrNull(code: Int, headers: Map<String, List<String>>): Long? =
        if (code == TOO_MANY_REQUESTS || code == UNAVAILABLE) retryAfterMillis(headers) else null

    private fun backoff(delayMs: Long) {
        try {
            sleeper(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled while waiting out dropbox throttling", interrupted)
        }
    }

    private companion object {
        const val API_BASE = "https://api.dropboxapi.com/2"
        const val LIST_FOLDER = "/files/list_folder"
        const val LIST_FOLDER_CONTINUE = "/files/list_folder/continue"
        const val GET_METADATA = "/files/get_metadata"
        const val GET_TEMPORARY_LINK = "/files/get_temporary_link"
        const val POST = "POST"
        const val TOO_MANY_REQUESTS = 429
        const val UNAVAILABLE = 503
        const val PATH_NOT_FOUND = "path/not_found"
        const val MAX_THROTTLE_RETRIES = 3

        /**
         * Fifteen minutes, against a documented four-hour link lifetime. Derived from the spec
         * rather than guessed, then pitched well under it: the document states a maximum, and a
         * maximum is not a promise. The asymmetry that decided Graph's still applies — too long
         * costs a refused read, too short costs one cheap metadata POST, about four an hour
         * against the hundreds of ranged reads a book already makes.
         */
        const val TEMPORARY_LINK_TTL_MS = 15 * 60 * 1000L

        /**
         * Dropbox pages `list_folder` at 500 entries by default, so this allows a 250,000-file
         * account before giving up. The bound exists because `has_more` is server-controlled:
         * a cursor that always reports more is an unbounded loop, not a big folder.
         */
        const val MAX_PAGES = 500

        val JSON_TYPE = mapOf("Content-Type" to "application/json")
    }
}
