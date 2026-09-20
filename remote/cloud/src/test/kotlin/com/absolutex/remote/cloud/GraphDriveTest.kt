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

/** OneDrive over Graph: paging, path addressing, throttling, and where the token may go. */
class GraphDriveTest {

    private class FakeGraph : HttpCall {
        data class Sent(val url: String, val headers: Map<String, String>)

        val sent = mutableListOf<Sent>()
        val ranged = mutableListOf<Sent>()
        private val bodies = ArrayDeque<HttpResponse>()

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
            return HttpStreamResponse(
                code = 206,
                stream = ByteArrayInputStream(ByteArray(4)),
                headers = mapOf("Content-Range" to listOf("bytes 0-3/4")),
            )
        }
    }

    /** Canned: what is under test is the paging and the trust checks, not the JSON. */
    private class FakeParser(
        private val pages: ArrayDeque<DrivePage> = ArrayDeque(),
        private val item: DriveItem? = null,
        private val error: String? = null,
    ) : GraphParser {
        override fun page(body: String): DrivePage = pages.removeFirstOrNull() ?: DrivePage(emptyList(), null)
        override fun item(body: String): DriveItem = item ?: DriveItem("id", "n", 0, false, null)
        override fun errorCode(body: String): String? = error
    }

    private fun file(name: String, size: Long = 100, url: String? = DOWNLOAD) =
        DriveItem("id-$name", name, size, isFolder = false, downloadUrl = url)

    /** A fixed header: these tests are about Graph, not about the token lifecycle. */
    private val authorized = AuthorizedRequest { call -> call(mapOf("Authorization" to "Bearer access-1")) }

    private fun drive(http: FakeGraph, parser: GraphParser) =
        GraphDrive(http, authorized, parser, BASE) { }

    // --- paging ------------------------------------------------------------------------------

    @Test fun `a folder's pages are followed and concatenated`() {
        val http = FakeGraph().apply { enqueue(200); enqueue(200) }
        val parser = FakeParser(ArrayDeque(listOf(
            DrivePage(listOf(file("a.cbz")), "$BASE/me/drive/root/children?\$skiptoken=x"),
            DrivePage(listOf(file("b.cbz")), null),
        )))
        val items = drive(http, parser).listFolder("/Comics")
        assertEquals(listOf("a.cbz", "b.cbz"), items.map { it.name })
        assertEquals("a second page must actually be fetched", 2, http.sent.size)
    }

    @Test fun `a single page does not fetch a second`() {
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(ArrayDeque(listOf(DrivePage(listOf(file("a.cbz")), null))))
        drive(http, parser).listFolder("/Comics")
        assertEquals(1, http.sent.size)
    }

    // --- the trust decision in @odata.nextLink -------------------------------------------------

    @Test fun `a next link pointing at another host is refused before the token follows it`() {
        // The link arrives in a server response and this client sends a bearer token to whatever
        // it names, so an unchecked nextLink is a token exfiltration primitive.
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(ArrayDeque(listOf(
            DrivePage(listOf(file("a.cbz")), "https://evil.example/steal?t=1"),
        )))
        try {
            drive(http, parser).listFolder("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue("got ${expected.message}", expected.message?.contains("left the api host") == true)
        }
        assertEquals("no request may be made to the foreign host", 1, http.sent.size)
    }

    @Test fun `a next link downgraded to http is refused`() {
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(ArrayDeque(listOf(
            DrivePage(listOf(file("a.cbz")), "http://graph.microsoft.com/v1.0/me/drive/root/children"),
        )))
        try {
            drive(http, parser).listFolder("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("left the api host") == true)
        }
    }

    @Test fun `a refused next link does not name the whole link`() {
        // A rejected link is attacker-chosen text; a legitimate one carries a skip token.
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(ArrayDeque(listOf(
            DrivePage(emptyList(), "https://evil.example/steal?token=SECRETVALUE"),
        )))
        val message = runCatching { drive(http, parser).listFolder("/c") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("the link leaked into: $message", !message.contains("SECRETVALUE"))
    }

    @Test fun `an endless page chain gives up rather than looping for ever`() {
        val http = FakeGraph()
        val parser = object : GraphParser {
            override fun page(body: String) = DrivePage(emptyList(), "$BASE/me/drive/root/children?\$skiptoken=x")
            override fun item(body: String) = file("x")
            override fun errorCode(body: String): String? = null
        }
        repeat(600) { http.enqueue(200) }
        try {
            drive(http, parser).listFolder("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("paged past") == true)
        }
    }

    // --- addressing ----------------------------------------------------------------------------

    @Test fun `the drive root uses the plain children url, not an empty path selector`() {
        val http = FakeGraph().apply { enqueue(200) }
        drive(http, FakeParser()).listFolder("")
        assertEquals("$BASE/me/drive/root/children", http.sent.single().url)
    }

    @Test fun `a path is escaped segment by segment, so a space cannot end the selector`() {
        val http = FakeGraph().apply { enqueue(200) }
        drive(http, FakeParser()).listFolder("/My Comics/Batman + Robin")
        val url = http.sent.single().url
        assertTrue("space must be %20, not +: $url", url.contains("My%20Comics"))
        assertTrue("a literal plus must be escaped: $url", url.contains("Batman%20%2B%20Robin"))
        assertTrue("separators must survive: $url", url.contains("/me/drive/root:/"))
    }

    // --- where the token may and may not go ------------------------------------------------------

    @Test fun `metadata calls carry the bearer token`() {
        val http = FakeGraph().apply { enqueue(200) }
        drive(http, FakeParser()).listFolder("/Comics")
        assertEquals("Bearer access-1", http.sent.single().headers["Authorization"])
    }

    @Test fun `a ranged read of the download url carries no authorization at all`() {
        // The download url is pre-authenticated. Attaching the account's bearer token would be
        // handing that token to a third-party storage host for no benefit whatsoever.
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(item = file("a.cbz", size = 4))
        drive(http, parser).open("/Comics/a.cbz").use { it.readAt(0, 4) }
        val ranged = http.ranged.single()
        assertEquals(DOWNLOAD, ranged.url)
        assertNull("the account token must not reach the storage host", ranged.headers["Authorization"])
        assertEquals("bytes=0-3", ranged.headers["Range"])
    }

    @Test fun `opening a folder is refused rather than range-read`() {
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(item = DriveItem("id", "Comics", 0, isFolder = true, downloadUrl = null))
        try {
            drive(http, parser).open("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not a file") == true)
        }
    }

    @Test fun `an item with no download url fails clearly instead of opening nothing`() {
        val http = FakeGraph().apply { enqueue(200) }
        val parser = FakeParser(item = file("a.cbz", url = null))
        try {
            drive(http, parser).open("/Comics/a.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no download url") == true)
        }
    }

    // --- failures --------------------------------------------------------------------------------

    @Test fun `a missing item reads as not found rather than a bare status`() {
        val http = FakeGraph().apply { enqueue(404, """{"error":{"code":"itemNotFound"}}""") }
        try {
            drive(http, FakeParser(error = "itemNotFound")).itemAt("/nope.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not found") == true)
        }
    }

    @Test fun `throttling is waited out and then the call succeeds`() {
        val http = FakeGraph().apply {
            enqueue(429, headers = mapOf("Retry-After" to listOf("2")))
            enqueue(200)
        }
        drive(http, FakeParser()).listFolder("/Comics")
        assertEquals(2, http.sent.size)
    }

    @Test fun `endless throttling gives up instead of hammering graph`() {
        val http = FakeGraph()
        repeat(8) { http.enqueue(429) }
        try {
            drive(http, FakeParser()).listFolder("/Comics")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("throttled") == true)
        }
    }

    @Test fun `no failure message carries the url or the token`() {
        val http = FakeGraph().apply { enqueue(500) }
        val message = runCatching { drive(http, FakeParser()).listFolder("/Comics") }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue("url leaked: $message", !message.contains("graph.microsoft.com"))
        assertTrue("token leaked: $message", !message.contains("access-1"))
    }

    @Test fun `a server error stays a status failure, not a not-found`() {
        val http = FakeGraph().apply { enqueue(500) }
        try {
            drive(http, FakeParser()).listFolder("/Comics")
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(500, expected.code)
        }
    }

    private companion object {
        const val BASE = "https://graph.microsoft.com/v1.0"
        const val DOWNLOAD = "https://storage.example/download?pre=authenticated"
    }
}
