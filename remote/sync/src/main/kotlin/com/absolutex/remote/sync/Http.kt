package com.absolutex.remote.sync

import java.net.URI

// LAN servers may sleep and spin up slowly; connect 10s, read 30s.
const val CONNECT_TIMEOUT_MS = 10_000
const val READ_TIMEOUT_MS = 30_000

internal const val HTTP_OK = 200
internal const val HTTP_PARTIAL = 206
internal const val HTTP_NO_CONTENT = 204
internal const val HTTP_BAD_REQUEST = 400
internal const val HTTP_UNAUTHORIZED = 401
internal const val HTTP_NOT_FOUND = 404

internal const val AUTHORIZATION = "Authorization"
internal const val API_KEY_HEADER = "X-API-Key"
internal const val RANGE = "Range"

internal val JSON_HEADERS: Map<String, String> = mapOf("Content-Type" to "application/json")

internal const val DATE_HEADER = "Date"

/**
 * Text response. [serverDateMs] is the server's `Date` header in epoch millis, or null when
 * the server sent none (or it did not parse): the clock-skew correction observes the server
 * clock through this field, never through the body.
 */
data class HttpResponse(val code: Int, val body: String, val serverDateMs: Long? = null)

class HttpBytesResponse(val code: Int, val bytes: ByteArray, val serverDateMs: Long? = null)

interface HttpCall {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
    fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse
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
            return HttpResponse(connection.responseCode, connection.readBody(), connection.serverDate())
        } finally {
            connection.disconnect()
        }
    }

    override fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): HttpBytesResponse {
        val connection = openConnection(method, url, headers)
        try {
            return HttpBytesResponse(connection.responseCode, connection.readBytesBody(), connection.serverDate())
        } finally {
            connection.disconnect()
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
        // Komga/Kavita often sit behind reverse proxies; follow their redirects platform-side.
        connection.instanceFollowRedirects = true
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
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun java.net.HttpURLConnection.readBytesBody(): ByteArray {
        val stream = if (responseCode >= HTTP_BAD_REQUEST) errorStream else inputStream
        return stream?.use { it.readBytes() } ?: ByteArray(0)
    }

    /**
     * The server's clock as observed on this response. `getHeaderFieldDate` parses the
     * RFC-1123 `Date` every HTTP server sends; 0 (absent or unparseable) reads as unknown.
     */
    private fun java.net.HttpURLConnection.serverDate(): Long? =
        getHeaderFieldDate(DATE_HEADER, 0L).takeIf { it > 0L }
}
