package com.absolutex.remote.sync

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kavita REST client over [HttpCall].
 *
 * Endpoints verified against the live OpenAPI
 * (https://raw.githubusercontent.com/Kareadita/Kavita/develop/openapi.json,
 * human docs https://wiki.kavitareader.com/guides/api and https://www.kavitareader.com/docs/api/):
 * POST /api/Account/login, POST /api/Account/refresh-token, GET /api/Library/libraries,
 * POST /api/Series/v2, GET /api/Reader/get-progress, POST /api/Reader/progress.
 * Calls whose documented shape proved ambiguous are marked TODO(experimental) below.
 */
class KavitaClient(private val http: HttpCall, baseUrl: String) {
    private val root = baseUrl.trimEnd('/')

    // Memory only: tokens live here and are never written to disk by this class. Persisting them
    // (remember-me) is the caller's job through a CredentialStore the user explicitly opted into.
    var token: String? = null
        private set
    var refreshToken: String? = null
        private set

    /**
     * API-key auth: the key rides as the bearer token (assumption — the lead validates on a
     * live server). A rejected key surfaces as 401 on first use, never silently.
     */
    fun setBearerToken(token: String) {
        this.token = token
        this.refreshToken = token
    }

    fun login(username: String, password: CharArray): Unit {
        val body = JSONObject().put("username", username).put("password", password.concatToString()).toString()
        val response = http.request("POST", "$root/api/Account/login", JSON_HEADERS, body)
        if (response.code != HTTP_OK) throw IOException("kavita login failed: ${response.code}")
        val session = parseKavitaSession(response.body)
        token = session.token
        refreshToken = session.refreshToken
    }

    fun libraries(): List<LibraryRef> {
        val response = authedRequest("GET", "/api/Library/libraries", null)
        if (response.code != HTTP_OK) throw IOException("kavita libraries failed: ${response.code}")
        return parseKavitaLibraries(response.body)
    }

    fun seriesPage(pageNumber: Int, pageSize: Int): List<SeriesRef> {
        // TODO(experimental): SeriesFilterV2Dto has no required fields in the published OpenAPI,
        // so this minimal filter body is unverified — validate against a live server.
        val body = JSONObject().put("statements", JSONArray()).put("limitTo", pageSize).toString()
        val response = authedRequest("POST", "/api/Series/v2?PageNumber=$pageNumber&PageSize=$pageSize", body)
        if (response.code != HTTP_OK) throw IOException("kavita series failed: ${response.code}")
        return parseKavitaSeries(response.body)
    }

    fun getProgress(chapterId: Int): RemoteProgress? {
        val response = authedRequest("GET", "/api/Reader/get-progress?chapterId=$chapterId", null)
        if (response.code == HTTP_NOT_FOUND) return null
        if (response.code != HTTP_OK) throw IOException("kavita progress GET failed: ${response.code}")
        return parseKavitaProgress(response.body)
    }

    fun saveProgress(progress: KavitaProgress): Unit {
        val body = JSONObject()
            .put("volumeId", progress.volumeId)
            .put("chapterId", progress.chapterId)
            .put("pageNum", progress.pageNum)
            .put("seriesId", progress.seriesId)
            .put("libraryId", progress.libraryId)
            .toString()
        val response = authedRequest("POST", "/api/Reader/progress", body)
        if (response.code != HTTP_OK) throw IOException("kavita progress PUT failed: ${response.code}")
    }

    private fun bearer(): Map<String, String> {
        val current = token ?: throw IOException("not logged in")
        return mapOf(AUTHORIZATION to "Bearer $current")
    }

    /** Module-visible for library browsing ([KavitaLibrary]); auth refresh included. */
    internal fun authedRequest(method: String, path: String, body: String?): HttpResponse {
        // One retry after a single refresh; a second 401 means the refresh itself is dead.
        val first = http.request(method, root + path, bearer(), body)
        if (first.code != HTTP_UNAUTHORIZED) return first
        refreshSession()
        return http.request(method, root + path, bearer(), body)
    }

    private fun refreshSession(): Unit {
        // TODO(experimental): the published OpenAPI types this response as TokenRequestDto (an
        // echo of the request), so the live refresh payload shape is unverified — validate live.
        val body = JSONObject().put("token", token).put("refreshToken", refreshToken).toString()
        val response = http.request("POST", "$root/api/Account/refresh-token", JSON_HEADERS, body)
        if (response.code != HTTP_OK) throw IOException("kavita refresh failed: ${response.code}")
        val session = parseKavitaSession(response.body)
        token = session.token
        refreshToken = session.refreshToken
    }
}
