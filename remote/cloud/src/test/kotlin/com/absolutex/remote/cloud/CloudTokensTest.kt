package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStatusException
import com.absolutex.remote.core.HttpStreamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import org.junit.Test

/**
 * The token endpoint: redeeming a code and refreshing an access token.
 *
 * These test the token logic, not JSON — the parser is injected, so the fake below returns
 * canned values and the provider modules test their real parsers under Robolectric.
 */
class CloudTokensTest {

    private class FakeHttp : HttpCall {
        data class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

        val sent = mutableListOf<Sent>()
        private val queue = ArrayDeque<HttpResponse>()

        fun enqueue(code: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) {
            queue += HttpResponse(code, body, headers = headers)
        }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            sent += Sent(method, url, headers, body)
            return queue.removeFirstOrNull() ?: throw IOException("no queued response")
        }

        override fun requestBytes(
            method: String,
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpBytesResponse = throw UnsupportedOperationException("token fake speaks text only")

        override fun requestStream(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): HttpStreamResponse = throw UnsupportedOperationException("token fake speaks text only")
    }

    /** Canned, because what is under test here is the exchange, not the parsing. */
    private class FakeParser(
        private val access: String = "access-1",
        private val refresh: String? = "refresh-1",
        private val expires: Long? = 3600L,
        private val error: String? = null,
    ) : TokenParser {
        override fun tokens(body: String) =
            TokenResponse(access.toCharArray(), refresh?.toCharArray(), expires)

        override fun errorCode(body: String): String? = error
    }

    private fun endpoint(
        http: FakeHttp,
        parser: TokenParser = FakeParser(),
        slept: MutableList<Long> = mutableListOf(),
    ) = CloudTokenEndpoint(http, TOKEN_URL, "client-1", parser) { slept += it }

    /** The body as name → value, decoded, so assertions read what the server would. */
    private fun form(body: String?): Map<String, String> =
        body.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") to
                java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }

    // --- redeeming a code -----------------------------------------------------------------

    @Test fun `redeeming posts the code and the verifier that proves the flow`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http).redeem("the-code", "the-verifier".toCharArray(), "absolutex://oauth").close()

        val sent = http.sent.single()
        assertEquals("POST", sent.method)
        assertEquals(TOKEN_URL, sent.url)
        assertEquals("application/x-www-form-urlencoded", sent.headers["Content-Type"])
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to "the-code",
                "redirect_uri" to "absolutex://oauth",
                "client_id" to "client-1",
                "code_verifier" to "the-verifier",
            ),
            form(sent.body),
        )
    }

    @Test fun `redeeming returns what the parser read`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http).redeem("c", "v".toCharArray(), "r").use { tokens ->
            assertEquals("access-1", tokens.accessToken.concatToString())
            assertEquals("refresh-1", tokens.refreshToken?.concatToString())
            assertEquals(3600L, tokens.expiresInSeconds)
        }
    }

    @Test fun `no client secret is ever sent, because PKCE means there is none`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http).redeem("c", "v".toCharArray(), "r").close()
        val body = http.sent.single().body.orEmpty()
        assertTrue("a client_secret appeared in $body", !body.contains("client_secret"))
    }

    @Test fun `a hostile value cannot become a second form parameter`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http).redeem("c&client_secret=leaked", "v".toCharArray(), "r").close()
        // Decoded back it is one value, and no extra parameter appeared.
        assertEquals("c&client_secret=leaked", form(http.sent.single().body)["code"])
        assertEquals(5, form(http.sent.single().body).size)
    }

    // --- refreshing -----------------------------------------------------------------------

    @Test fun `refreshing trades the refresh token for a new access token`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http).refresh("old-refresh".toCharArray()).close()
        assertEquals(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "old-refresh",
                "client_id" to "client-1",
            ),
            form(http.sent.single().body),
        )
    }

    @Test fun `a refresh that reissues no refresh token says so, rather than inventing one`() {
        val http = FakeHttp().apply { enqueue(200, "{}") }
        endpoint(http, FakeParser(refresh = null)).refresh("old".toCharArray()).use { tokens ->
            assertNull("the caller must keep the token it already had", tokens.refreshToken)
            assertEquals("access-1", tokens.accessToken.concatToString())
        }
    }

    // --- a grant that is gone, versus a request we built wrong ------------------------------

    @Test fun `invalid_grant asks for sign-in, because no retry can rescue it`() {
        val http = FakeHttp().apply { enqueue(400, """{"error":"invalid_grant"}""") }
        try {
            endpoint(http, FakeParser(error = "invalid_grant")).refresh("stale".toCharArray())
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
    }

    @Test fun `any other 400 stays a plain failure, because it is our bug and not the user's`() {
        val http = FakeHttp().apply { enqueue(400, """{"error":"invalid_request"}""") }
        try {
            endpoint(http, FakeParser(error = "invalid_request")).refresh("t".toCharArray())
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            // Catching this type IS the assertion: ReauthRequiredException is a sibling under
            // IOException, not an HttpStatusException, so a reauth would escape this clause and
            // fail the test. An explicit `!is` check here would be vacuous — the compiler says so.
            assertEquals(400, expected.code)
        }
    }

    @Test fun `a 401 asks for sign-in`() {
        val http = FakeHttp().apply { enqueue(401) }
        try {
            endpoint(http).refresh("t".toCharArray())
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
    }

    @Test fun `an unparseable error body is not itself a reauth`() {
        // A parser that throws on a 400 body must not be read as "the grant is gone" — that
        // would send the user to sign in again over a body we simply could not read.
        val http = FakeHttp().apply { enqueue(400, "<html>gateway</html>") }
        val throwing = object : TokenParser {
            override fun tokens(body: String) = throw IOException("not json")
            override fun errorCode(body: String) = throw IOException("not json")
        }
        try {
            endpoint(http, throwing).refresh("t".toCharArray())
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(400, expected.code)
        }
    }

    // --- rate limits ------------------------------------------------------------------------

    @Test fun `a rate limit is waited out and then the exchange succeeds`() {
        val http = FakeHttp().apply {
            enqueue(429, headers = mapOf("Retry-After" to listOf("2")))
            enqueue(200, "{}")
        }
        val slept = mutableListOf<Long>()
        endpoint(http, slept = slept).refresh("t".toCharArray()).close()
        assertEquals(listOf(2_000L), slept)
        assertEquals(2, http.sent.size)
    }

    @Test fun `an endless rate limit gives up rather than hammering the endpoint`() {
        val http = FakeHttp()
        repeat(RATE_LIMIT_RESPONSES) { http.enqueue(429) }
        val slept = mutableListOf<Long>()
        try {
            endpoint(http, slept = slept).refresh("t".toCharArray())
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("rate limited") == true)
        }
        assertTrue("bounded waits, got ${slept.size}", slept.size <= 4)
        assertTrue("a zero backoff is a hot loop", slept.all { it > 0 })
    }

    // --- secrets -----------------------------------------------------------------------------

    @Test fun `closing zeroes both tokens`() {
        val tokens = TokenResponse("a".toCharArray(), "r".toCharArray(), 1L)
        tokens.close()
        assertTrue(tokens.accessToken.all { it == '\u0000' })
        assertTrue(tokens.refreshToken?.all { it == '\u0000' } == true)
    }

    @Test fun `no failure message carries the token or the url`() {
        val secret = "super-secret-refresh-token"
        val messages = mutableListOf<String>()
        listOf(400 to "invalid_grant", 400 to "invalid_request", 401 to null, 500 to null).forEach { (code, err) ->
            val http = FakeHttp().apply { enqueue(code, "{}") }
            runCatching { endpoint(http, FakeParser(error = err)).refresh(secret.toCharArray()) }
                .exceptionOrNull()?.let { messages += "${it.message}" }
        }
        assertTrue("expected failures to assert on", messages.size == 4)
        messages.forEach {
            assertTrue("token leaked into: $it", !it.contains(secret))
            assertTrue("url leaked into: $it", !it.contains("login.example"))
        }
    }

    private companion object {
        const val TOKEN_URL = "https://login.example/oauth2/token"
        const val RATE_LIMIT_RESPONSES = 8
    }
}
