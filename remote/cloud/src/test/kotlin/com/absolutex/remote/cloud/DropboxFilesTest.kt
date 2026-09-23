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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Dropbox over its v2 HTTP API: cursors, temporary links, and where the token may go. */
class DropboxFilesTest {

    private class FakeDropbox : HttpCall {
        data class Sent(val url: String, val headers: Map<String, String>, val body: String?)

        val sent = mutableListOf<Sent>()
        val ranged = mutableListOf<Sent>()
        private val bodies = ArrayDeque<HttpResponse>()

        /** The status to refuse a ranged url with, or null to serve it. Per-url on purpose. */
        var refuses: (String) -> Int? = { null }

        fun enqueue(code: Int, body: String = "{}", headers: Map<String, List<String>> = emptyMap()) {
            bodies += HttpResponse(code, body, headers = headers)
        }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse {
            sent += Sent(url, headers, body)
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
            ranged += Sent(url, headers, null)
            val refusal = refuses(url)
            if (refusal != null) return HttpStreamResponse(refusal, InputStream.nullInputStream())
            return HttpStreamResponse(
                code = 206,
                stream = ByteArrayInputStream(ByteArray(4)),
                headers = mapOf("Content-Range" to listOf("bytes 0-3/4")),
            )
        }
    }

    /**
     * Canned: what is under test is the paging, the addressing and the trust decisions, not the
     * JSON. The bodies it is handed are recorded so the request side of the seam is still pinned.
     */
    private class FakeJson(
        private val pages: ArrayDeque<DropboxPage> = ArrayDeque(),
        private val entry: DropboxEntry? = null,
        private val links: ArrayDeque<String> = ArrayDeque(),
        private val summary: String? = null,
    ) : DropboxJson {
        val pathsAsked = mutableListOf<String>()
        val cursorsAsked = mutableListOf<String>()

        override fun pathBody(path: String): String {
            pathsAsked += path
            return """{"path":"$path"}"""
        }

        override fun cursorBody(cursor: String): String {
            cursorsAsked += cursor
            return """{"cursor":"$cursor"}"""
        }

        override fun page(body: String): DropboxPage = pages.removeFirstOrNull() ?: DropboxPage(emptyList(), null)
        override fun entry(body: String): DropboxEntry = entry ?: file("a.cbz")
        override fun temporaryLink(body: String): String = links.removeFirstOrNull() ?: LINK
        override fun errorSummary(body: String): String? = summary
    }

    private val authorized = AuthorizedRequest { call -> call(mapOf("Authorization" to "Bearer access-1")) }

    private fun files(http: HttpCall, json: DropboxJson) =
        DropboxFiles(http, authorized, json, API, linkTtlMillis = TTL, clock = { 0L }) { }

    // --- paging ------------------------------------------------------------------------------

    @Test fun `a folder is read to the end by following the cursor`() {
        val http = FakeDropbox().apply { enqueue(200); enqueue(200) }
        val json = FakeJson(
            pages = ArrayDeque(
                listOf(
                    DropboxPage(listOf(file("a.cbz")), cursor = "c1"),
                    DropboxPage(listOf(file("b.cbz")), cursor = null),
                ),
            ),
        )
        val entries = files(http, json).listFolder("/Comics")

        assertEquals(listOf("a.cbz", "b.cbz"), entries.map { it.name })
        assertEquals("the second page must use continue, not list_folder again",
            listOf("$API/files/list_folder", "$API/files/list_folder/continue"), http.sent.map { it.url })
        assertEquals(listOf("c1"), json.cursorsAsked)
    }

    @Test fun `a cursor that always reports more is bounded instead of looping for ever`() {
        // has_more is server-controlled. Without the bound a cursor that always points to another
        // page is an unbounded loop, not a big folder.
        val http = FakeDropbox()
        repeat(600) { http.enqueue(200) }
        val json = FakeJson(ArrayDeque(List(600) { DropboxPage(listOf(file("a.cbz")), cursor = "c") }))
        try {
            files(http, json).listFolder("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("paged past") == true)
        }
        assertTrue("bounded, got ${http.sent.size} calls", http.sent.size <= 501)
    }

    @Test fun `the account root is addressed as the empty string, not as a slash`() {
        // Dropbox rejects "/" for the root and wants "". Sending the slash is a 409 on the very
        // first listing a new account makes, which is the worst possible first impression.
        val http = FakeDropbox().apply { enqueue(200) }
        val json = FakeJson()
        files(http, json).listFolder("/")
        assertEquals(listOf(""), json.pathsAsked)
    }

    // --- addressing and auth ----------------------------------------------------------------

    @Test fun `every api call carries the account token`() {
        val http = FakeDropbox().apply { enqueue(200) }
        files(http, FakeJson()).entryAt("/Comics/a.cbz")
        assertEquals("Bearer access-1", http.sent.single().headers["Authorization"])
    }

    @Test fun `a ranged read of the temporary link carries no authorization at all`() {
        // The link is pre-authenticated. Attaching the account's bearer token would be handing
        // that token to a third-party storage host for no benefit whatsoever.
        val http = FakeDropbox().apply { enqueue(200); enqueue(200) }
        files(http, FakeJson()).open("/Comics/a.cbz").use { it.readAt(0, 4) }

        val ranged = http.ranged.single()
        assertEquals(LINK, ranged.url)
        assertNull("the account token must not reach the storage host", ranged.headers["Authorization"])
        assertEquals("bytes=0-3", ranged.headers["Range"])
    }

    @Test fun `opening a file costs one metadata call and one link call, not one per block`() {
        val http = FakeDropbox().apply { enqueue(200); enqueue(200) }
        files(http, FakeJson()).open("/Comics/a.cbz").use {
            it.readAt(0, 4)
            it.readAt(0, 4)
        }
        assertEquals("a second block must not re-resolve a fresh link", 2, http.sent.size)
    }

    // --- the temporary link expires ----------------------------------------------------------

    @Test fun `a link refused before its ttl is re-resolved, not reported as a sign-in`() {
        // The clock never moves here, so the cache believes its first link is fresh for the whole
        // read — the only thing that can produce a second link is the refusal itself. And that
        // refusal cannot be about the account: the ranged request carries no Authorization header.
        val http = FakeDropbox().apply { enqueue(200); enqueue(200); enqueue(200) }
        http.refuses = { if (it == LINK) UNAUTHORIZED else null }
        val json = FakeJson(links = ArrayDeque(listOf(LINK, LINK_2)))

        files(http, json).open("/Comics/a.cbz").use { it.readAt(0, 4) }

        assertEquals("the refused link must be replaced, not re-presented",
            listOf(LINK, LINK_2), http.ranged.map { it.url })
    }

    @Test fun `a link past its ttl is re-resolved before it can be refused`() {
        var now = 0L
        val http = FakeDropbox().apply { enqueue(200); enqueue(200); enqueue(200) }
        val json = FakeJson(links = ArrayDeque(listOf(LINK, LINK_2)))
        val drive = DropboxFiles(http, authorized, json, API, linkTtlMillis = TTL, clock = { now }) { }

        drive.open("/Comics/a.cbz").use {
            it.readAt(0, 4)
            now += TTL
            it.readAt(0, 4)
        }

        assertEquals(listOf(LINK, LINK_2), http.ranged.map { it.url })
    }

    // --- refusals ----------------------------------------------------------------------------

    @Test fun `opening a folder is refused rather than range-read`() {
        val http = FakeDropbox().apply { enqueue(200) }
        val json = FakeJson(entry = DropboxEntry("id", "Comics", "/comics", 0, isFolder = true))
        try {
            files(http, json).open("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not a file") == true)
        }
        assertTrue("a folder must never be range-read", http.ranged.isEmpty())
    }

    @Test fun `a missing file reads as not found even though dropbox answers 409`() {
        // Dropbox answers endpoint errors with 409 and puts the reason in error_summary. Treating
        // the status alone as the answer would tell the user "conflict" for a file that is simply
        // not there.
        val http = FakeDropbox().apply { enqueue(409, """{"error_summary":"path/not_found/..."}""") }
        val json = FakeJson(summary = "path/not_found/...")
        try {
            files(http, json).entryAt("/nope.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not found") == true)
        }
    }

    @Test fun `a 409 that is not a missing path keeps its status for the caller to match on`() {
        val http = FakeDropbox().apply { enqueue(409, """{"error_summary":"path/conflict/file/..."}""") }
        val json = FakeJson(summary = "path/conflict/file/...")
        try {
            files(http, json).entryAt("/a.cbz")
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(409, expected.code)
        }
    }

    @Test fun `a server error stays a status failure, not a not-found`() {
        val http = FakeDropbox().apply { enqueue(500) }
        try {
            files(http, FakeJson()).entryAt("/a.cbz")
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(500, expected.code)
        }
    }

    // --- throttling --------------------------------------------------------------------------

    @Test fun `throttling is waited out for as long as Retry-After asks`() {
        val http = FakeDropbox().apply {
            enqueue(429, headers = mapOf("Retry-After" to listOf("3")))
            enqueue(200)
        }
        val slept = mutableListOf<Long>()
        val drive = DropboxFiles(http, authorized, FakeJson(), API, TTL, { 0L }) { slept += it }
        drive.entryAt("/a.cbz")
        assertEquals(listOf(3_000L), slept)
    }

    @Test fun `endless throttling gives up after a bounded number of waits`() {
        val http = FakeDropbox()
        repeat(10) { http.enqueue(429) }
        val slept = mutableListOf<Long>()
        val drive = DropboxFiles(http, authorized, FakeJson(), API, TTL, { 0L }) { slept += it }
        try {
            drive.entryAt("/a.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("throttled") == true)
        }
        assertTrue("bounded waits, got ${slept.size}", slept.size <= 4)
        assertTrue("a zero backoff is a hot loop", slept.all { it > 0 })
    }

    // --- secrets -----------------------------------------------------------------------------

    @Test fun `no failure message carries the token, the link or the path`() {
        // The link carries its own credential and the path is the user's private file name, so
        // neither belongs in a message that may reach a log or a bug report.
        val messages = mutableListOf<String>()
        for (code in listOf(409, 500, 401)) {
            val http = FakeDropbox().apply { enqueue(code, """{"error_summary":"path/conflict/..."}""") }
            runCatching { files(http, FakeJson(summary = "path/conflict/...")).entryAt(SECRET_PATH) }
                .exceptionOrNull()?.let { messages += "${it.message}" }
        }
        assertTrue("expected failures to assert on", messages.isNotEmpty())
        messages.forEach { message ->
            assertTrue("token leaked into: $message", !message.contains("access-1"))
            assertTrue("link leaked into: $message", !message.contains("dropboxusercontent"))
            assertTrue("path leaked into: $message", !message.contains("Very Private"))
        }
    }

    private companion object {
        const val API = "https://api.dropboxapi.com/2"
        const val LINK = "https://dl.dropboxusercontent.com/apitl/1/aaaa"
        const val LINK_2 = "https://dl.dropboxusercontent.com/apitl/1/bbbb"
        const val TTL = 900_000L
        const val UNAUTHORIZED = 401
        const val SECRET_PATH = "/Very Private/a.cbz"

        fun file(name: String, size: Long = 4) =
            DropboxEntry("id-$name", name, "/comics/${name.lowercase()}", size, isFolder = false)
    }
}
