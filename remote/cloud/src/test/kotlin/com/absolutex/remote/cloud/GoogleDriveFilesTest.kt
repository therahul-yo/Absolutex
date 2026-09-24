package com.absolutex.remote.cloud

import com.absolutex.remote.core.HttpBytesResponse
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.HttpStatusException
import com.absolutex.remote.core.HttpStreamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** Google Drive over v3: page tokens, guarded ids, and where the bearer token may go. */
class GoogleDriveFilesTest {

    private class FakeDrive : HttpCall {
        data class Sent(val url: String, val headers: Map<String, String>)

        val sent = mutableListOf<Sent>()
        val ranged = mutableListOf<Sent>()
        private val bodies = ArrayDeque<HttpResponse>()

        /** The status a ranged read is refused with, or null to serve it. */
        var refuseRanged: Int? = null

        fun enqueue(code: Int, body: String = "{}", headers: Map<String, List<String>> = emptyMap()) {
            bodies += HttpResponse(code, body, headers = headers)
        }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            sent += Sent(url, headers)
            return bodies.removeFirstOrNull() ?: throw IOException("no queued response for $url")
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
        ): HttpStreamResponse {
            ranged += Sent(url, headers)
            val refusal = refuseRanged
            if (refusal != null) return HttpStreamResponse(refusal, InputStream.nullInputStream())
            return HttpStreamResponse(
                code = 206,
                stream = ByteArrayInputStream(ByteArray(4)),
                headers = mapOf("Content-Range" to listOf("bytes 0-3/4")),
            )
        }
    }

    /** Canned: under test are the paging, the guards and the trust decisions, not the JSON. */
    private class FakeJson(
        private val pages: ArrayDeque<GoogleDrivePage> = ArrayDeque(),
        private val file: GoogleDriveFile? = null,
        private val reason: String? = null,
    ) : GoogleDriveJson {
        override fun page(body: String) = pages.removeFirstOrNull() ?: GoogleDrivePage(emptyList(), null)
        override fun file(body: String) = file ?: book("a.cbz")
        override fun errorReason(body: String): String? = reason
    }

    private val authorized = AuthorizedRequest { call -> call(ACCOUNT_HEADERS) }

    private fun drive(http: HttpCall, json: GoogleDriveJson, sleeper: (Long) -> Unit = {}) =
        GoogleDriveFiles(http, authorized, { ACCOUNT_HEADERS }, json, API, sleeper)

    private fun queryOf(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    // --- paging ------------------------------------------------------------------------------

    @Test fun `a folder is read to the end by following the page token`() {
        val http = FakeDrive().apply { enqueue(200); enqueue(200) }
        val json = FakeJson(
            ArrayDeque(
                listOf(
                    GoogleDrivePage(listOf(book("a.cbz")), nextPageToken = "t1"),
                    GoogleDrivePage(listOf(book("b.cbz")), nextPageToken = null),
                ),
            ),
        )
        val files = drive(http, json).listFolder()

        assertEquals(listOf("a.cbz", "b.cbz"), files.map { it.name })
        assertEquals("the first page carries no token", null, queryOf(http.sent[0].url)["pageToken"])
        assertEquals("t1", queryOf(http.sent[1].url)["pageToken"])
    }

    @Test fun `a page token that never ends is bounded instead of looping for ever`() {
        val http = FakeDrive()
        repeat(600) { http.enqueue(200) }
        val json = FakeJson(ArrayDeque(List(600) { GoogleDrivePage(listOf(book("a.cbz")), "t") }))
        try {
            drive(http, json).listFolder()
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("paged past") == true)
        }
        assertTrue("bounded, got ${http.sent.size} calls", http.sent.size <= 501)
    }

    @Test fun `a listing asks for the largest page drive allows, and only the fields it reads`() {
        // Drive defaults to 100 a page; asking for its documented maximum is a tenth of the round
        // trips for a large library, and a field mask keeps each response to what is parsed.
        val http = FakeDrive().apply { enqueue(200) }
        drive(http, FakeJson()).listFolder()
        val query = queryOf(http.sent.single().url)
        assertEquals("1000", query["pageSize"])
        assertEquals("nextPageToken,files(id,name,mimeType,size)", query["fields"])
    }

    // --- the query language ------------------------------------------------------------------

    @Test fun `the folder query carries only the id, never a name`() {
        val http = FakeDrive().apply { enqueue(200) }
        drive(http, FakeJson()).listFolder("abc_DEF-123")
        assertEquals("'abc_DEF-123' in parents and trashed = false", queryOf(http.sent.single().url)["q"])
    }

    @Test fun `an id that could break out of the query is refused before anything is sent`() {
        // The whole point of guarding rather than escaping: a value shaped to close the quoted
        // literal and add its own clause never reaches Drive's query language at all.
        val http = FakeDrive()
        for (hostile in listOf("x' or name contains 'a", "x\\' in parents", "../files", "a b", "")) {
            try {
                drive(http, FakeJson()).listFolder(hostile)
                fail("expected IOException for <$hostile>")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("not a google drive file id") == true)
            }
        }
        assertTrue("nothing may be sent for a refused id", http.sent.isEmpty())
    }

    @Test fun `an id that could escape the url path is refused before a byte is read`() {
        val http = FakeDrive()
        try {
            drive(http, FakeJson()).open("abc/../../oauth2")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not a google drive file id") == true)
        }
        assertTrue(http.sent.isEmpty() && http.ranged.isEmpty())
    }

    // --- where the token goes ----------------------------------------------------------------

    @Test fun `every metadata call carries the account token`() {
        val http = FakeDrive().apply { enqueue(200) }
        drive(http, FakeJson()).fileAt("abc")
        assertEquals("Bearer access-1", http.sent.single().headers["Authorization"])
    }

    @Test fun `a ranged read goes to the api host with alt=media, carrying the token in a header`() {
        // Drive has no pre-authenticated link, so the token has to ride on the read — which makes
        // where it goes the property to pin: the issuing origin, over https, in a header.
        val http = FakeDrive().apply { enqueue(200) }
        drive(http, FakeJson()).open("abc").use { it.readAt(0, 4) }

        val read = http.ranged.single()
        assertEquals("$API/files/abc?alt=media", read.url)
        assertEquals("Bearer access-1", read.headers["Authorization"])
        assertEquals("bytes=0-3", read.headers["Range"])
    }

    @Test fun `no url ever carries the token, though drive would accept it as a query parameter`() {
        val http = FakeDrive().apply { enqueue(200); enqueue(200) }
        val files = drive(http, FakeJson())
        files.listFolder()
        files.open("abc").use { it.readAt(0, 4) }

        (http.sent + http.ranged).forEach { sent ->
            assertTrue("token in url: ${sent.url}", !sent.url.contains("access_token"))
            assertTrue("token in url: ${sent.url}", !sent.url.contains("oauth_token"))
            assertTrue("token in url: ${sent.url}", !sent.url.contains("access-1"))
        }
    }

    @Test fun `a cleartext api base is refused at construction`() {
        try {
            GoogleDriveFiles(FakeDrive(), authorized, { ACCOUNT_HEADERS }, FakeJson(), "http://www.googleapis.com")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("https") == true)
        }
    }

    // --- what cannot be opened ---------------------------------------------------------------

    @Test fun `folders, shortcuts and google-native documents are refused rather than range-read`() {
        for (mime in listOf(FOLDER, "application/vnd.google-apps.shortcut", "application/vnd.google-apps.document")) {
            val http = FakeDrive().apply { enqueue(200) }
            val json = FakeJson(file = GoogleDriveFile("abc", "x.cbz", mime, sizeBytes = null))
            try {
                drive(http, json).open("abc")
                fail("expected IOException for $mime")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("not downloadable") == true)
            }
            assertTrue("$mime must never be range-read", http.ranged.isEmpty())
        }
    }

    @Test fun `a folder is recognised as a folder`() {
        assertTrue(GoogleDriveFile("abc", "Comics", FOLDER, null).isFolder)
        assertTrue(!book("a.cbz").isFolder)
    }

    // --- refusals and throttling -------------------------------------------------------------

    @Test fun `a token refused on a ranged read asks for sign-in after exactly one attempt`() {
        // The #96 gate from the other direction. With a stable url the supplier has nothing newer,
        // so a transport carrying a bearer token cannot be talked into a second attempt. The
        // message is imperfect for an expired token — that gap is named in the class KDoc.
        val http = FakeDrive().apply { enqueue(200) }
        http.refuseRanged = 401
        try {
            drive(http, FakeJson()).open("abc").use { it.readAt(0, 4) }
            fail("expected ReauthRequiredException")
        } catch (expected: ReauthRequiredException) {
            assertTrue(expected.message?.contains("sign-in required") == true)
        }
        assertEquals("one attempt, no retry", 1, http.ranged.size)
    }

    @Test fun `a missing file reads as not found`() {
        val http = FakeDrive().apply { enqueue(404) }
        try {
            drive(http, FakeJson(reason = "notFound")).fileAt("abc")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not found") == true)
        }
    }

    @Test fun `a 403 rate limit is waited out, because google throttles with 403 as well as 429`() {
        val http = FakeDrive().apply {
            enqueue(403, headers = mapOf("Retry-After" to listOf("2")))
            enqueue(200)
        }
        val slept = mutableListOf<Long>()
        drive(http, FakeJson(reason = "userRateLimitExceeded"), sleeper = { slept += it }).fileAt("abc")
        assertEquals(listOf(2_000L), slept)
    }

    @Test fun `a 403 that is not a rate limit fails at once rather than waiting`() {
        val http = FakeDrive().apply { enqueue(403) }
        val slept = mutableListOf<Long>()
        try {
            drive(http, FakeJson(reason = "insufficientFilePermissions"), sleeper = { slept += it }).fileAt("abc")
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(403, expected.code)
        }
        assertTrue("a permission error must not be retried", slept.isEmpty())
    }

    @Test fun `endless throttling gives up after a bounded number of waits`() {
        val http = FakeDrive()
        repeat(10) { http.enqueue(429) }
        val slept = mutableListOf<Long>()
        try {
            drive(http, FakeJson(), sleeper = { slept += it }).fileAt("abc")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("throttled") == true)
        }
        assertTrue("bounded waits, got ${slept.size}", slept.size <= 4)
        assertTrue("a zero backoff is a hot loop", slept.all { it > 0 })
    }

    @Test fun `no failure message carries the token or the url`() {
        val messages = mutableListOf<String>()
        for (code in listOf(404, 403, 500)) {
            val http = FakeDrive().apply { enqueue(code) }
            runCatching { drive(http, FakeJson(reason = "x")).fileAt("SecretFileId123") }
                .exceptionOrNull()?.let { messages += "${it.message}" }
        }
        assertTrue("expected failures to assert on", messages.isNotEmpty())
        messages.forEach { message ->
            assertTrue("token leaked into: $message", !message.contains("access-1"))
            assertTrue("url leaked into: $message", !message.contains("googleapis"))
            assertTrue("id leaked into: $message", !message.contains("SecretFileId123"))
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
        val ACCOUNT_HEADERS = mapOf("Authorization" to "Bearer access-1")

        fun book(name: String) = GoogleDriveFile("id-${name.hashCode()}", name, "application/zip", 4)
    }
}
