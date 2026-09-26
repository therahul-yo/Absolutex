package com.absolutex.remote.cloud

import com.absolutex.remote.core.HTTP_FORBIDDEN
import com.absolutex.remote.core.HTTP_OK
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpStatusException
import java.io.IOException

/**
 * One item in Google Drive.
 *
 * Addressed by [id], never by path: Drive's own reference says a name "isn't necessarily unique
 * within a folder", so two books called `Batman 01.cbz` side by side are legal, and a path would
 * have to pick one of them silently. [sizeBytes] is null for folders and shortcuts, which Drive
 * reports without a size.
 */
data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    /**
     * Bytes stored in Drive, as opposed to a Google-native item. Every `application/vnd.google-apps.*`
     * type — folder, shortcut, Doc, Sheet — is refused by `alt=media`, which "only works if the
     * file is stored in Drive". A prefix check covers the whole family without having to know
     * every member of it.
     */
    val isDownloadable: Boolean get() = !mimeType.startsWith(GOOGLE_APPS_MIME_PREFIX)
}

/** A page of items, plus the token for the next page when more remain. */
data class GoogleDrivePage(val files: List<GoogleDriveFile>, val nextPageToken: String?)

/**
 * Reads Drive's JSON. Injected for the reason established in [TokenParser]: `:remote:cloud` stays
 * plain Kotlin/JVM and JSON-free. Read-only, unlike [DropboxJson] — every Drive call here is a
 * GET, so nothing user-supplied is ever serialised into a body.
 */
interface GoogleDriveJson {
    /** A `files.list` response: its items and the `nextPageToken`, if any. */
    fun page(body: String): GoogleDrivePage

    /** A single `files.get` metadata response. */
    fun file(body: String): GoogleDriveFile

    /** The first `error.errors[].reason`, or null when the body carries none. */
    fun errorReason(body: String): String?
}

/**
 * Google Drive over the v3 REST API, by raw HTTP — no provider SDK, per the lane's constraint.
 *
 * Written against the Drive v3 discovery document (revision 20260913), which is Google's
 * machine-readable reference and was fetched directly; the prose guides were not reachable from
 * where this was built, so anything taken from them is marked where it is used.
 *
 * **Unlike Graph and Dropbox, there is no pre-authenticated link to read from.** The File
 * resource's only link fields are `webContentLink` (for a browser session), `webViewLink`,
 * `exportLinks` (Docs Editors files) and a short-lived `thumbnailLink` for thumbnails. Content comes
 * from `files.get` with `alt=media`, and that request carries the account's bearer token. So the
 * rule that held for the other two — never send the token to a storage host — cannot hold here
 * as written. Its *purpose* can: the token goes only to the origin that issued it, which is the
 * API host itself; only over https, enforced at construction; and never along a redirect, which
 * `HttpUrlConnectionCall` already refuses to follow.
 *
 * **Two ways Drive's API would put a secret or a user's string where it does not belong, both
 * declined.** First, `access_token` is a documented query parameter on every Drive method — a
 * sanctioned way to put the token in a URL. It is never used; the token travels only in the
 * `Authorization` header. Second, `files.list` filters with `q`, a query language of quoted
 * string literals, so a folder *name* in `q` would need escaping by that language's rules. No
 * name is ever put there. Only IDs go in, and only after [requireDriveId] has refused anything
 * outside a conservative charset — a guard that fails closed, not an escaper and not a claim
 * about the format Drive promises. Names are matched client-side, where they are data.
 *
 * **What this does not yet do, stated rather than implied.** A ranged read carries the token, so
 * one that outlasts the token's lifetime is refused mid-book, and [HttpRangeTransport] cannot
 * refresh: it reports that as [ReauthRequiredException], which is the wrong message. It cannot
 * yet tell a 403 rate limit from a 403 permission either, since it never reads a refusal's body.
 * Metadata calls do not share either problem — they go through [AuthorizedRequest] and read the
 * reason — so this is the streaming path only.
 */
class GoogleDriveFiles(
    private val http: HttpCall,
    private val authorized: AuthorizedRequest,
    /**
     * The same account's authorization, for the ranged reads [open] makes. A signed-in account
     * supplies `{ session.withAccessToken { it } }`: read per request, so a token refreshed by a
     * metadata call is picked up by the next block without reopening the book.
     */
    private val accessHeaders: () -> Map<String, String>,
    private val json: GoogleDriveJson,
    private val apiBaseUrl: String = API_BASE,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {

    init {
        // The token rides on every ranged read to this base, so a cleartext one would send it in
        // the clear on every block of every book. Refused here rather than trusted to config.
        require(apiBaseUrl.startsWith(HTTPS_SCHEME)) { "the drive api base must be https" }
    }

    /** Everything in a folder, following page tokens. [ROOT_FOLDER_ID] is the user's My Drive. */
    @Throws(IOException::class)
    fun listFolder(folderId: String = ROOT_FOLDER_ID): List<GoogleDriveFile> {
        val query = "'${requireDriveId(folderId)}' in parents and trashed = false"
        val files = mutableListOf<GoogleDriveFile>()
        var pageToken: String? = null
        var pages = 0
        while (true) {
            pages++
            if (pages > MAX_PAGES) throw IOException("folder paged past $MAX_PAGES pages")
            val page = json.page(getJson(listUrl(query, pageToken)))
            files += page.files
            pageToken = page.nextPageToken ?: return files
        }
    }

    /** The metadata for one item. */
    @Throws(IOException::class)
    fun fileAt(fileId: String): GoogleDriveFile =
        json.file(getJson("$apiBaseUrl/files/${requireDriveId(fileId)}?fields=${encode(FILE_FIELDS)}"))

    /**
     * A ranged transport over one file's bytes.
     *
     * The url is built here from the configured base and a guarded ID, never taken from a
     * response, so no server-controlled string decides where the token is sent.
     */
    @Throws(IOException::class)
    fun open(fileId: String): HttpRangeTransport {
        val file = fileAt(fileId)
        if (!file.isDownloadable) {
            throw IOException("not downloadable: folders, shortcuts and Google-native documents have no bytes")
        }
        val url = "$apiBaseUrl/files/${requireDriveId(fileId)}?alt=media"
        return HttpRangeTransport(http, { url }, file.sizeBytes, accessHeaders, sleeper)
    }

    /**
     * The page token is opaque server text, but it can only ever be a query *value* here: it is
     * percent-encoded into a url this class built, so it cannot change the host or the path.
     */
    private fun listUrl(query: String, pageToken: String?): String {
        val base = "$apiBaseUrl/files?q=${encode(query)}&pageSize=$PAGE_SIZE&fields=${encode(LIST_FIELDS)}"
        return if (pageToken == null) base else "$base&pageToken=${encode(pageToken)}"
    }

    /** A GET carrying the account's token, retried only for throttling and only as asked. */
    private fun getJson(url: String): String {
        var attempt = 0
        while (true) {
            val response = authorized.request { headers -> http.request(GET, url, headers, null) }
            if (response.code == HTTP_OK) return response.body
            val reason = runCatching { json.errorReason(response.body) }.getOrNull()
            val retryMs = throttleDelayOrNull(response.code, reason, response.headers)
            if (retryMs == null) throw failureFor(response.code, reason)
            attempt++
            if (attempt > MAX_THROTTLE_RETRIES) throw IOException("google drive throttled after $attempt attempts")
            backoff(retryMs)
        }
    }

    /** Status and reason to an error. Never names the url: it carries the file id and the query. */
    private fun failureFor(code: Int, reason: String?): IOException = when {
        code == NOT_FOUND || reason == REASON_NOT_FOUND -> IOException("not found on this drive")
        else -> HttpStatusException(code, "google drive request failed with $code")
    }

    /**
     * Google answers a rate limit with 429, and also with **403** carrying `rateLimitExceeded` or
     * `userRateLimitExceeded` — taken from Google's error guide, which is not in the discovery
     * document. Treating every 403 as a refusal would fail a request that only had to wait;
     * treating every 403 as a rate limit would retry a real permission error. The reason decides.
     */
    private fun throttleDelayOrNull(code: Int, reason: String?, headers: Map<String, List<String>>): Long? {
        val throttled = code == TOO_MANY_REQUESTS || code == UNAVAILABLE ||
            (code == HTTP_FORBIDDEN && reason in RATE_LIMIT_REASONS)
        return if (throttled) retryAfterMillis(headers) else null
    }

    private fun backoff(delayMs: Long) {
        try {
            sleeper(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled while waiting out google drive throttling", interrupted)
        }
    }

    /**
     * Refuses anything but a conservative ID charset before it reaches a url or the `q` query.
     * Fails closed on purpose: an ID outside this set is refused loudly rather than escaped,
     * because escaping it would mean implementing Drive's query-language quoting — the rule this
     * whole class is arranged to avoid. The message does not echo the value, which may have come
     * from a hostile response.
     */
    private fun requireDriveId(id: String): String {
        if (!DRIVE_ID.matches(id)) throw IOException("not a google drive file id")
        return id
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val API_BASE = "https://www.googleapis.com/drive/v3"
        const val HTTPS_SCHEME = "https://"
        const val GET = "GET"
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
        const val UNAVAILABLE = 503
        const val REASON_NOT_FOUND = "notFound"
        const val MAX_THROTTLE_RETRIES = 3

        /** Drive's documented maximum; the default is 100, so this is a tenth of the round trips. */
        const val PAGE_SIZE = 1000

        /** At [PAGE_SIZE] a page, a 500,000-item folder before giving up on a token that never ends. */
        const val MAX_PAGES = 500

        const val LIST_FIELDS = "nextPageToken,files(id,name,mimeType,size)"
        const val FILE_FIELDS = "id,name,mimeType,size"

        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
        val DRIVE_ID = Regex("[A-Za-z0-9_-]+")
    }
}

/**
 * The alias for the user's My Drive root. Used by Google's search guide; it is **not** in the
 * discovery document, so it is the one part of this contract taken on trust rather than read.
 */
const val ROOT_FOLDER_ID = "root"

private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
private const val GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps."
