package com.absolutex.remote.core

import java.io.IOException
import java.net.URI

/**
 * Per-server cleartext policy plus manual redirect following, over any [HttpCall].
 *
 * `validateServerUrl` at save time is UX, not enforcement: every request path takes
 * `baseUrl` as-is, so the scheme is checked here, immediately before every request. The
 * platform must not follow redirects on our behalf (`instanceFollowRedirects` stays off in
 * [HttpUrlConnectionCall]) — an https URL silently landing on http would bypass this
 * check — so redirects are followed here, with the same scheme check on every hop and
 * auth headers stripped when the host changes (a cross-host redirect must never carry the
 * Authorization or API-key header to a stranger).
 */
class CleartextHttpCall(
    private val delegate: HttpCall,
    private val allowCleartext: Boolean,
) : HttpCall {

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        checkScheme(url)
        var currentMethod = method
        var currentUrl = url
        var currentHeaders = headers
        var currentBody = body
        var response = delegate.request(currentMethod, currentUrl, currentHeaders, currentBody)
        var next = followTarget(currentUrl, response)
        var hops = 0
        while (next != null && hops < MAX_REDIRECTS) {
            hops++
            if (response.code in GET_CONVERTING_CODES && currentMethod != GET_METHOD) {
                currentMethod = GET_METHOD
                currentBody = null
            }
            if (URI(next).host != URI(currentUrl).host) {
                currentHeaders = currentHeaders - AUTHORIZATION - API_KEY_HEADER
            }
            currentUrl = next
            response = delegate.request(currentMethod, currentUrl, currentHeaders, currentBody)
            next = followTarget(currentUrl, response)
        }
        return response
    }

    override fun requestBytes(method: String, url: String, headers: Map<String, String>): HttpBytesResponse {
        checkScheme(url)
        var currentMethod = method
        var currentUrl = url
        var currentHeaders = headers
        var response = delegate.requestBytes(currentMethod, currentUrl, currentHeaders)
        var next = followTarget(currentUrl, response.code, response.headers)
        var hops = 0
        while (next != null && hops < MAX_REDIRECTS) {
            hops++
            if (response.code in GET_CONVERTING_CODES && currentMethod != GET_METHOD) {
                currentMethod = GET_METHOD
            }
            if (URI(next).host != URI(currentUrl).host) {
                currentHeaders = currentHeaders - AUTHORIZATION - API_KEY_HEADER
            }
            currentUrl = next
            response = delegate.requestBytes(currentMethod, currentUrl, currentHeaders)
            next = followTarget(currentUrl, response.code, response.headers)
        }
        return response
    }

    private fun checkScheme(url: String) {
        if (!schemeAllowed(schemeOf(url))) {
            throw IOException("plain HTTP is not allowed for this server")
        }
    }

    private fun schemeAllowed(scheme: String?): Boolean =
        scheme == "https" || (scheme == "http" && allowCleartext)

    /** Null scheme (unparseable URL) fails the check above — it can only come from a bug. */
    private fun schemeOf(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        return parsed.scheme?.lowercase()
    }

    private fun followTarget(currentUrl: String, response: HttpResponse): String? =
        followTarget(currentUrl, response.code, response.headers)

    private fun followTarget(currentUrl: String, code: Int, headers: Map<String, List<String>>): String? {
        if (code !in REDIRECT_CODES) return null
        val location = headers.entries
            .firstOrNull { it.key.equals("location", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?: return null
        val next = URI(currentUrl).resolve(location).toString()
        checkScheme(next)
        return next
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val GET_METHOD = "GET"
        private const val HTTP_TEMPORARY_REDIRECT = 307
        private const val HTTP_PERMANENT_REDIRECT = 308
        private val REDIRECT_CODES = setOf(
            java.net.HttpURLConnection.HTTP_MOVED_PERM,
            java.net.HttpURLConnection.HTTP_MOVED_TEMP,
            java.net.HttpURLConnection.HTTP_SEE_OTHER,
            HTTP_TEMPORARY_REDIRECT,
            HTTP_PERMANENT_REDIRECT,
        )
        private val GET_CONVERTING_CODES = setOf(
            java.net.HttpURLConnection.HTTP_MOVED_PERM,
            java.net.HttpURLConnection.HTTP_MOVED_TEMP,
            java.net.HttpURLConnection.HTTP_SEE_OTHER,
        )
    }
}

/** Enforces the server's cleartext opt-in on every request and follows redirects safely. */
fun HttpCall.withCleartextPolicy(allowCleartext: Boolean): HttpCall =
    CleartextHttpCall(this, allowCleartext)
