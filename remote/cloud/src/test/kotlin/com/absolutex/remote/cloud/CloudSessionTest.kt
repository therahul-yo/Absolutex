package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStreamResponse
import com.absolutex.remote.core.InMemoryCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Presenting a token, and what happens when the provider stops accepting it. */
class CloudSessionTest {

    private val credentials = InMemoryCredentialStore()
    private val store = CloudTokenStore(credentials)

    /** Counts refreshes and hands back a new access token each time, as a rotating provider does. */
    private class RefreshingHttp(private val slowMs: Long = 0) : HttpCall {
        val refreshes = AtomicInteger(0)
        var failWith: Int? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            val n = refreshes.incrementAndGet()
            if (slowMs > 0) Thread.sleep(slowMs)
            failWith?.let { return HttpResponse(it, """{"error":"invalid_grant"}""") }
            return HttpResponse(HTTP_OK, "refreshed-$n")
        }

        override fun requestBytes(
            method: String,
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpBytesResponse = throw UnsupportedOperationException()

        override fun requestStream(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): HttpStreamResponse = throw UnsupportedOperationException()
    }

    /** Reads the canned body as the access token, so each refresh is distinguishable. */
    private class BodyParser(private val error: String? = "invalid_grant") : TokenParser {
        override fun tokens(body: String) = TokenResponse(body.toCharArray(), "rotated".toCharArray(), 3600L)
        override fun errorCode(body: String): String? = error
    }

    private fun session(http: HttpCall, parser: TokenParser = BodyParser()) =
        CloudSession(RECORD, store, CloudTokenEndpoint(http, TOKEN_URL, "client-1", parser) { })

    private fun signedIn(access: String = "access-1", refresh: String? = "refresh-1") {
        store.save(RECORD, TokenResponse(access.toCharArray(), refresh?.toCharArray(), 3600L))
    }

    private fun bearerOf(headers: Map<String, String>) = headers["Authorization"]

    // --- the ordinary path ------------------------------------------------------------------

    @Test fun `a stored token is presented as a bearer header`() {
        signedIn()
        val header = session(RefreshingHttp()).withAccessToken { bearerOf(it) }
        assertEquals("Bearer access-1", header)
    }

    @Test fun `an accepted token is not refreshed, because nothing said it was stale`() {
        signedIn()
        val http = RefreshingHttp()
        session(http).withAccessToken { bearerOf(it) }
        assertEquals("a working token must not cost a token request", 0, http.refreshes.get())
    }

    // --- refresh on refusal -------------------------------------------------------------------

    @Test fun `a refused token is refreshed once and the call is retried`() {
        signedIn()
        val http = RefreshingHttp()
        var attempts = 0
        val header = session(http).withAccessToken { headers ->
            attempts++
            if (attempts == 1) throw ReauthRequiredException("the server said no")
            bearerOf(headers)
        }
        assertEquals(2, attempts)
        assertEquals(1, http.refreshes.get())
        assertEquals("the retry must use the NEW token", "Bearer refreshed-1", header)
    }

    @Test fun `the refreshed tokens are persisted, so the next cold start is already signed in`() {
        signedIn()
        var first = true
        session(RefreshingHttp()).withAccessToken {
            if (first) { first = false; throw ReauthRequiredException("no") }
        }
        assertEquals("refreshed-1", store.accessToken(RECORD)?.concatToString())
        assertEquals(
            "a rotated refresh token must replace the old one",
            "rotated",
            store.refreshToken(RECORD)?.concatToString(),
        )
    }

    @Test fun `no stored access token refreshes rather than failing`() {
        // Cold start after storage was cleared but the refresh token survived: this must take
        // the same route as a refusal, not a separate one.
        store.save(RECORD, TokenResponse("tmp".toCharArray(), "refresh-1".toCharArray(), 1L))
        credentials.clear(CloudTokenStore.accessTokenService(RECORD))
        val http = RefreshingHttp()
        val header = session(http).withAccessToken { bearerOf(it) }
        assertEquals("Bearer refreshed-1", header)
        assertEquals(1, http.refreshes.get())
    }

    @Test fun `a second refusal after a fresh token gives up instead of looping`() {
        signedIn()
        val http = RefreshingHttp()
        var attempts = 0
        try {
            session(http).withAccessToken {
                attempts++
                throw ReauthRequiredException("still no")
            }
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("still no") == true)
        }
        assertEquals("exactly one retry, never a loop", 2, attempts)
        assertEquals(1, http.refreshes.get())
    }

    // --- a grant that is gone -------------------------------------------------------------

    @Test fun `no refresh token at all reports the original refusal`() {
        signedIn(refresh = null)
        credentials.clear(CloudTokenStore.refreshTokenService(RECORD))
        val http = RefreshingHttp()
        try {
            session(http).withAccessToken { throw ReauthRequiredException("the server said no") }
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertEquals("the server said no", expected.message)
        }
        assertEquals("nothing to refresh with, so nothing may be sent", 0, http.refreshes.get())
    }

    @Test fun `a refused refresh token clears storage, so no dead credential is presented again`() {
        signedIn()
        val http = RefreshingHttp().apply { failWith = HTTP_BAD_REQUEST }
        try {
            session(http).withAccessToken { throw ReauthRequiredException("no") }
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
        assertNull("a dead access token must not survive", store.accessToken(RECORD))
        assertNull("a dead refresh token must not survive", store.refreshToken(RECORD))
    }

    @Test fun `a transient server failure does not sign the account out`() {
        // A 500 is not "your grant is gone". Clearing storage here would turn an outage into a
        // forced re-authentication for every account.
        signedIn()
        val http = RefreshingHttp().apply { failWith = HTTP_SERVER_ERROR }
        try {
            session(http).withAccessToken { throw ReauthRequiredException("no") }
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue("got ${expected::class.simpleName}", expected !is ReauthRequiredException)
        }
        assertEquals("refresh-1", store.refreshToken(RECORD)?.concatToString())
    }

    @Test fun `signing out forgets everything`() {
        signedIn()
        session(RefreshingHttp()).signOut()
        assertNull(store.accessToken(RECORD))
        assertNull(store.refreshToken(RECORD))
    }

    // --- the race that rotation makes fatal ---------------------------------------------------

    @Test fun `concurrent refusals refresh once, because a rotated token can only be spent once`() {
        signedIn()
        // Slow enough that every thread is inside withAccessToken before the winner finishes.
        val http = RefreshingHttp(slowMs = 50)
        val session = session(http)
        val barrier = CyclicBarrier(THREADS)
        val firstAttempt = ThreadLocal.withInitial { true }
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val work = (0 until THREADS).map {
                Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    session.withAccessToken { headers ->
                        if (firstAttempt.get()) {
                            firstAttempt.set(false)
                            throw ReauthRequiredException("expired for everyone at once")
                        }
                        bearerOf(headers)
                    }
                }
            }
            val headers = pool.invokeAll(work).map { it.get() }
            // The point of the whole design: a second refresh would spend a refresh token the
            // first one already invalidated, breaking the chain and signing the user out.
            assertEquals("refreshed more than once under concurrency", 1, http.refreshes.get())
            assertTrue("every caller must end up with the winner's token", headers.all { it == "Bearer refreshed-1" })
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val RECORD = "rec-1"
        const val TOKEN_URL = "https://login.example/oauth2/token"
        const val THREADS = 8
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_SERVER_ERROR = 500
    }
}
