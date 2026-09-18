package com.absolutex.remote.sync

import java.io.IOException
import org.json.JSONObject

/** Komga auth: HTTP Basic or an API key sent as X-API-Key (both per Komga OpenAPI 1.26.3). */
sealed interface KomgaAuth {
    data class Basic(val username: String, val password: CharArray) : KomgaAuth
    data class ApiKey(val key: CharArray) : KomgaAuth
}

const val KOMGA_PAGE_SIZE = 20

/**
 * Komga REST client over [HttpCall].
 *
 * Endpoints verified against the live OpenAPI
 * (https://raw.githubusercontent.com/gotson/komga/refs/heads/master/komga/docs/openapi.json,
 * v1.26.3, human docs https://komga.org/docs/openapi/komga-api):
 * POST /api/v1/series/list, POST /api/v1/books/list, GET /api/v1/books/{id},
 * GET /api/v1/books/{id}/pages, GET /api/v1/books/{id}/pages/{n},
 * PATCH + DELETE /api/v1/books/{id}/read-progress.
 */
class KomgaClient(
    private val http: HttpCall,
    baseUrl: String,
    private val auth: KomgaAuth,
) {
    private val root = baseUrl.trimEnd('/')

    private fun authHeaders(): Map<String, String> = when (val current = auth) {
        is KomgaAuth.Basic -> mapOf(AUTHORIZATION to basicCredentials(current.username, current.password))
        is KomgaAuth.ApiKey -> mapOf(API_KEY_HEADER to current.key.concatToString())
    }

    fun listSeries(page: Int, size: Int): KomgaPage<SeriesRef> {
        val response = http.request(
            "POST",
            "$root/api/v1/series/list?page=$page&size=$size",
            authHeaders() + JSON_HEADERS,
            "{}",
        )
        if (response.code != HTTP_OK) throw HttpStatusException(response.code, "series list failed: ${response.code}")
        return parseSeriesPage(response.body)
    }

    fun listAllSeries(): List<SeriesRef> = accumulatePages { page -> listSeries(page, KOMGA_PAGE_SIZE) }

    fun booksInSeries(seriesId: String, page: Int, size: Int): KomgaPage<BookRef> {
        // SearchConditionSeriesId shape per OpenAPI: {seriesId: {operator: "is", value: id}}.
        val condition = JSONObject()
            .put("seriesId", JSONObject().put("operator", "is").put("value", seriesId))
        val response = http.request(
            "POST",
            "$root/api/v1/books/list?page=$page&size=$size",
            authHeaders() + JSON_HEADERS,
            JSONObject().put("condition", condition).toString(),
        )
        if (response.code != HTTP_OK) throw HttpStatusException(response.code, "books list failed: ${response.code}")
        return parseBooksPage(response.body)
    }

    fun listAllBooksInSeries(seriesId: String): List<BookRef> =
        accumulatePages { page -> booksInSeries(seriesId, page, KOMGA_PAGE_SIZE) }

    fun listPages(bookId: String): List<PageRef> {
        val response = http.request("GET", "$root/api/v1/books/$bookId/pages", authHeaders(), null)
        if (response.code != HTTP_OK) throw HttpStatusException(response.code, "pages list failed: ${response.code}")
        return parsePages(response.body)
    }

    fun fetchPageBytes(bookId: String, pageNumber: Int, range: LongRange? = null): ByteArray {
        // Komga documents no Range support on page images; the header goes out opportunistically
        // and a full-body 200 counts the same as a 206.
        val extra = if (range == null) emptyMap() else mapOf(RANGE to "bytes=${range.first}-${range.last}")
        val response = http.requestBytes(
            "GET",
            "$root/api/v1/books/$bookId/pages/$pageNumber?zero_based=false",
            authHeaders() + extra,
        )
        if (response.code != HTTP_OK && response.code != HTTP_PARTIAL) {
            throw HttpStatusException(response.code, "page fetch failed: ${response.code}")
        }
        return response.bytes
    }

    fun getProgress(bookId: String): RemoteProgress? {
        // No dedicated progress GET exists; ReadProgress rides on BookDto and is absent pre-read.
        val response = http.request("GET", "$root/api/v1/books/$bookId", authHeaders(), null)
        if (response.code != HTTP_OK) throw HttpStatusException(response.code, "progress GET failed: ${response.code}")
        return parseBookWithProgress(response.body).progress
    }

    fun putProgress(bookId: String, page: Int, completed: Boolean): Unit {
        val body = JSONObject().put("page", page).put("completed", completed).toString()
        val response = http.request(
            "PATCH",
            "$root/api/v1/books/$bookId/read-progress",
            authHeaders() + JSON_HEADERS,
            body,
        )
        if (response.code != HTTP_NO_CONTENT) {
            throw HttpStatusException(response.code, "progress PUT failed: ${response.code}")
        }
    }

    fun clearProgress(bookId: String): Unit {
        val response = http.request(
            "DELETE",
            "$root/api/v1/books/$bookId/read-progress",
            authHeaders(),
            null,
        )
        if (response.code != HTTP_NO_CONTENT) {
            throw HttpStatusException(response.code, "progress DELETE failed: ${response.code}")
        }
    }
}
