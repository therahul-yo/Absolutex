package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStreamResponse
import com.absolutex.remote.core.InMemoryCredentialStore
import com.absolutex.remote.core.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A provider wired to a real [CloudSession], the way production composes them.
 *
 * Every other test in this module fakes one side of that seam. `GraphDriveTest` and
 * `DropboxFilesTest` hand the provider a fixed header that never expires; `CloudSessionTest`
 * refuses by *throwing*. Neither crosses the join, so neither can see whether an expired token
 * on a real provider call actually reaches the refresh.
 */
class ProviderRefreshTest {

    private val store = CloudTokenStore(InMemoryCredentialStore())

    /**
     * Serves both halves: the token endpoint mints `fresh-N`, and the API refuses the expired
     * `access-1` with a plain 401 *response* — which is what [HttpCall.request] returns for a
     * refused bearer token. It does not throw.
     */
    private class ExpiringApi(
        private val refusal: Int = UNAUTHORIZED,
        private val refuseAll: Boolean = false,
    ) : HttpCall {
        var refreshes = 0
        val presented = mutableListOf<String?>()

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            if (url == TOKEN_URL) {
                refreshes++
                return HttpResponse(HTTP_OK, "fresh-$refreshes")
            }
            val bearer = headers["Authorization"]
            presented += bearer
            val refused = refuseAll || bearer == "Bearer access-1"
            return if (refused) HttpResponse(refusal, "{}") else HttpResponse(HTTP_OK, "{}")
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

    private object Tokens : TokenParser {
        override fun tokens(body: String) = TokenResponse(body.toCharArray(), null, 3600L)
        override fun errorCode(body: String): String? = null
    }

    private fun session(http: HttpCall): CloudSession {
        store.save(RECORD, TokenResponse("access-1".toCharArray(), "refresh-1".toCharArray(), 3600L))
        return CloudSession(RECORD, store, CloudTokenEndpoint(http, TOKEN_URL, "client-1", Tokens) { })
    }

    @Test fun `an expired token on a graph call is refreshed once and the call succeeds`() {
        val http = ExpiringApi()
        val graph = GraphDrive(http, wire(session(http)), GraphJson, BASE) { }

        graph.itemAt("/Comics/a.cbz")

        assertEquals("the expired token must be refreshed, exactly once", 1, http.refreshes)
        assertEquals(listOf("Bearer access-1", "Bearer fresh-1"), http.presented)
    }

    @Test fun `an expired token on a dropbox call is refreshed once and the call succeeds`() {
        val http = ExpiringApi()
        val dropbox = DropboxFiles(http, wire(session(http)), DropboxJsonFake, BASE) { }

        dropbox.entryAt("/Comics/a.cbz")

        assertEquals("the expired token must be refreshed, exactly once", 1, http.refreshes)
        assertEquals(listOf("Bearer access-1", "Bearer fresh-1"), http.presented)
    }

    @Test fun `a token refused even after refreshing asks for sign-in instead of refreshing again`() {
        // Exactly one refresh. A token minted seconds ago and still refused means the grant is
        // gone, and a second refresh would spend a rotating refresh token for nothing.
        val http = ExpiringApi(refuseAll = true)
        val graph = GraphDrive(http, wire(session(http)), GraphJson, BASE) { }
        try {
            graph.itemAt("/Comics/a.cbz")
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
        assertEquals("bounded: one refresh, not a loop", 1, http.refreshes)
        assertEquals(2, http.presented.size)
    }

    @Test fun `a 403 does not spend a refresh`() {
        // 403 is scope, permission, or on Google a rate limit. A new token fixes none of them,
        // and refreshing would burn a rotation that the single flight exists to protect.
        val http = ExpiringApi(refusal = FORBIDDEN, refuseAll = true)
        val graph = GraphDrive(http, wire(session(http)), GraphJson, BASE) { }
        try {
            graph.itemAt("/Comics/a.cbz")
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(FORBIDDEN, expected.code)
        }
        assertEquals("a 403 must never reach the token endpoint", 0, http.refreshes)
    }

    private object GraphJson : GraphParser {
        override fun page(body: String) = DrivePage(emptyList(), null)
        override fun item(body: String) = DriveItem("id", "a.cbz", 4, isFolder = false, downloadUrl = "https://s/d")
        override fun errorCode(body: String): String? = null
    }

    private object DropboxJsonFake : DropboxJson {
        override fun pathBody(path: String) = "{}"
        override fun cursorBody(cursor: String) = "{}"
        override fun page(body: String) = DropboxPage(emptyList(), null)
        override fun entry(body: String) = DropboxEntry("id", "a.cbz", "/a.cbz", 4, isFolder = false)
        override fun temporaryLink(body: String) = "https://s/l"
        override fun errorSummary(body: String): String? = null
    }

    private companion object {
        const val RECORD = "account-1"
        const val TOKEN_URL = "https://login.example/token"
        const val BASE = "https://api.example"
        const val HTTP_OK = 200
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403

        /**
         * The production wiring. `AuthorizedRequest(session::withAccessToken)` compiles too, and
         * was what the KDoc used to prescribe — against it, both refresh tests above fail with
         * `HttpStatusException: ... failed with 401` and zero refreshes.
         */
        fun wire(session: CloudSession) = session.asAuthorizedRequest()
    }
}
