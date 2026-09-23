package com.absolutex.remote.cloud

import com.absolutex.remote.core.HTTP_FORBIDDEN
import com.absolutex.remote.core.HTTP_OK
import com.absolutex.remote.core.HTTP_UNAUTHORIZED
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpStatusException
import java.io.Closeable
import java.io.IOException

/**
 * What a provider's token endpoint gave back.
 *
 * Both tokens are CharArrays and this is [Closeable] for the house reason: a plaintext
 * credential with no bounded lifetime is what a heap dump carries away. [refreshToken] is
 * nullable because a refresh response need not reissue one — when it does not, the caller keeps
 * the token it already had rather than discarding it.
 */
class TokenResponse(
    val accessToken: CharArray,
    val refreshToken: CharArray?,
    val expiresInSeconds: Long?,
) : Closeable {
    override fun close() {
        accessToken.fill('\u0000')
        refreshToken?.fill('\u0000')
    }
}

/**
 * Reads a provider's token-endpoint bodies. Injected rather than implemented here so that
 * `:remote:cloud` stays plain Kotlin/JVM and JSON-free: `org.json` is Android-platform only, and
 * the Maven Central artifact carries a licence we will not take. The provider modules are Android
 * libraries regardless — the PKCE flow needs Custom Tabs and a redirect intent — so they parse
 * with the platform parser and test it under Robolectric, while these JVM tests pass a trivial
 * fake and exercise the token logic rather than the parsing.
 *
 * Two methods, because the error body needs reading too: RFC 6749 answers a revoked or expired
 * grant with **400 and `error=invalid_grant`**, and the status alone cannot tell that apart from
 * a request we simply built wrong. Only the first is worth sending the user back to a sign-in
 * screen for.
 */
interface TokenParser {
    /** A successful body. Throws if it is not one — a caller never sees a half-filled response. */
    fun tokens(body: String): TokenResponse

    /** The `error` code from a failure body, or null when there is none to read. */
    fun errorCode(body: String): String?
}

/**
 * A provider's OAuth token endpoint: redeem an authorisation code, and refresh when the access
 * token expires.
 *
 * No client secret anywhere, because PKCE means there is none to hold — the verifier proves this
 * is the same app that started the flow.
 *
 * **On secrets and the body.** [HttpCall.request] takes its body as a String, so the verifier and
 * the refresh token become immutable text that cannot be zeroed for as long as the JVM keeps it.
 * That is the seam's shape, not a choice made here; what this class does control, it bounds — it
 * never logs, never puts a token in the URL, and its failures carry a status and nothing else.
 */
class CloudTokenEndpoint(
    private val http: HttpCall,
    private val tokenUrl: String,
    private val clientId: String,
    private val parser: TokenParser,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {

    /** Exchanges an authorisation code, proving possession with the verifier that started it. */
    @Throws(IOException::class)
    fun redeem(code: String, verifier: CharArray, redirectUri: String): TokenResponse = post(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier.concatToString(),
        ),
    )

    /**
     * Trades a refresh token for a fresh access token. A provider that does not reissue a refresh
     * token leaves [TokenResponse.refreshToken] null, and the caller keeps the one it has.
     */
    @Throws(IOException::class)
    fun refresh(refreshToken: CharArray): TokenResponse = post(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken.concatToString(),
            "client_id" to clientId,
        ),
    )

    private fun post(form: Map<String, String>): TokenResponse {
        val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        var attempt = 0
        while (true) {
            val response = http.request(POST, tokenUrl, FORM_HEADERS, body)
            if (response.code == HTTP_OK) return parser.tokens(response.body)
            val retryMs = rateLimitDelayOrNull(response.code, response.headers)
            if (retryMs == null) throw failureFor(response.code, response.body)
            attempt++
            if (attempt > MAX_RATE_LIMIT_RETRIES) {
                throw IOException("token endpoint rate limited after $attempt attempts")
            }
            backoff(retryMs)
        }
    }

    /**
     * Status (and, for a 400, the body's `error`) to an exception.
     *
     * `invalid_grant` is the one worth separating: it means the grant is gone — revoked, expired,
     * or consent withdrawn — and no retry can rescue it, so the UI should offer a sign-in rather
     * than a try-again. Every other 400 is our own malformed request and stays a plain failure,
     * because telling a user to sign in again would be blaming them for our bug.
     */
    private fun failureFor(code: Int, body: String): IOException {
        val revoked = code == HTTP_BAD_REQUEST && runCatching { parser.errorCode(body) }
            .getOrNull() == INVALID_GRANT
        return when {
            revoked -> ReauthRequiredException("sign-in required: the grant is no longer valid")
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                ReauthRequiredException("sign-in required: the server rejected this account")
            else -> HttpStatusException(code, "token endpoint failed with $code")
        }
    }

    private fun rateLimitDelayOrNull(code: Int, headers: Map<String, List<String>>): Long? =
        if (code == HTTP_TOO_MANY_REQUESTS || code == HTTP_UNAVAILABLE) retryAfterMillis(headers) else null

    /** Sleeps, but stays cancellable: an interrupt ends the exchange rather than being swallowed. */
    private fun backoff(delayMs: Long) {
        try {
            sleeper(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled while waiting out a rate limit", interrupted)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val POST = "POST"
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503
        const val INVALID_GRANT = "invalid_grant"
        const val MAX_RATE_LIMIT_RETRIES = 3

        val FORM_HEADERS = mapOf("Content-Type" to "application/x-www-form-urlencoded")
    }
}
