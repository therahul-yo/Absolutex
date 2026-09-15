package com.absolutex.remote.sync

import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Komga client tests over hand-written JSON matching the verified OpenAPI shapes (SeriesDto,
 * BookDto + ReadProgressDto, PageDto). Robolectric provides the real org.json on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KomgaClientTest {

    private fun client(fake: FakeHttpCall, auth: KomgaAuth = KomgaAuth.ApiKey("key-1".toCharArray())) =
        KomgaClient(fake, "http://lan:25600/", auth)

    private fun seriesPage(ids: List<String>, last: Boolean): String {
        val content = ids.joinToString(",") { "{\"id\":\"$it\",\"name\":\"Series $it\"}" }
        return "{\"content\":[$content],\"last\":$last,\"totalPages\":2,\"number\":0}"
    }

    private fun booksPage(last: Boolean): String = "{\"content\":[" +
        "{\"id\":\"b1\",\"name\":\"Ch 1\",\"seriesId\":\"s1\",\"media\":{\"pagesCount\":24}}," +
        "{\"id\":\"b2\",\"name\":\"Ch 2\",\"seriesId\":\"s1\",\"media\":{\"pagesCount\":30}}" +
        "],\"last\":$last}"

    private fun bookWithProgress(): String = "{\"id\":\"b1\",\"name\":\"Ch 1\",\"seriesId\":\"s1\"," +
        "\"media\":{\"pagesCount\":24},\"readProgress\":{\"page\":6,\"completed\":false," +
        "\"created\":\"2026-09-01T09:00:00Z\",\"readDate\":\"2026-09-01T09:00:00Z\"," +
        "\"lastModified\":\"2026-09-01T10:00:00Z\",\"deviceId\":\"d\",\"deviceName\":\"n\"}}"

    private fun bookWithoutProgress(): String = "{\"id\":\"b1\",\"name\":\"Ch 1\",\"seriesId\":\"s1\"," +
        "\"media\":{\"pagesCount\":24}}"

    @Test fun basicAuthHeaderFormation() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, bookWithoutProgress()))
        client(fake, KomgaAuth.Basic("alice", "s3cret".toCharArray())).getProgress("b1")
        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertEquals(expected, fake.last.headers["Authorization"])
    }

    @Test fun apiKeyHeaderFormation() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, bookWithoutProgress()))
        client(fake).getProgress("b1")
        assertEquals("key-1", fake.last.headers["X-API-Key"])
        assertNull(fake.last.headers["Authorization"])
    }

    @Test fun seriesPagingAccumulates() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, seriesPage(listOf("s1", "s2"), false)))
        fake.enqueue(HttpResponse(200, seriesPage(listOf("s3"), true)))
        val all = client(fake).listAllSeries()
        assertEquals(listOf("s1", "s2", "s3"), all.map { it.id })
        assertTrue(fake.requests[0].url.contains("/api/v1/series/list?page=0&size=20"))
        assertTrue(fake.requests[1].url.contains("/api/v1/series/list?page=1&size=20"))
    }

    @Test fun seriesPagingStopsOnEmptyContent() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, seriesPage(emptyList(), false)))
        assertTrue(client(fake).listAllSeries().isEmpty())
        assertEquals(1, fake.requests.size)
    }

    @Test fun booksInSeriesSendsConditionAndParses() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, booksPage(true)))
        val page = client(fake).booksInSeries("s1", 0, 20)
        val condition = JSONObject(fake.last.body).getJSONObject("condition").getJSONObject("seriesId")
        assertEquals("is", condition.getString("operator"))
        assertEquals("s1", condition.getString("value"))
        assertEquals(listOf("b1", "b2"), page.items.map { it.id })
        assertEquals(24, page.items[0].pageCount)
        assertTrue(page.last)
    }

    @Test fun bookProgressParsed() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, bookWithProgress()))
        val progress = client(fake).getProgress("b1")
        assertEquals(6, progress?.page)
        assertEquals(false, progress?.completed)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(), progress?.updatedAt)
    }

    @Test fun bookWithoutProgressYieldsNull() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, bookWithoutProgress()))
        assertNull(client(fake).getProgress("b1"))
    }

    @Test fun corruptJsonDegradesToIOException() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, "{oops"))
        assertThrows(java.io.IOException::class.java) { client(fake).getProgress("b1") }
    }

    @Test fun missingContentFieldFails() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, "{\"last\":true}"))
        assertThrows(java.io.IOException::class.java) { client(fake).listSeries(0, 20) }
    }

    @Test fun pageRangeHeaderFormation() {
        val fake = FakeHttpCall()
        fake.enqueueBytes(HttpBytesResponse(206, byteArrayOf(1, 2, 3)))
        client(fake).fetchPageBytes("b1", 3, 0L..1023L)
        assertEquals("bytes=0-1023", fake.last.headers["Range"])
        assertTrue(fake.last.url.contains("/api/v1/books/b1/pages/3?zero_based=false"))
    }

    @Test fun pageFetchWithoutRangeSendsNoRangeHeader() {
        val fake = FakeHttpCall()
        fake.enqueueBytes(HttpBytesResponse(200, byteArrayOf(9)))
        val bytes = client(fake).fetchPageBytes("b1", 1)
        assertNull(fake.last.headers["Range"])
        assertEquals(1, bytes.size)
    }

    @Test fun putProgressUsesPatchAndExpects204() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(204, ""))
        client(fake).putProgress("b1", 6, false)
        assertEquals("PATCH", fake.last.method)
        val body = JSONObject(fake.last.body)
        assertEquals(6, body.getInt("page"))
        assertEquals(false, body.getBoolean("completed"))
        fake.enqueue(HttpResponse(400, "{}"))
        assertThrows(java.io.IOException::class.java) { client(fake).putProgress("b1", 6, false) }
    }

    @Test fun clearProgressUsesDelete() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(204, ""))
        client(fake).clearProgress("b1")
        assertEquals("DELETE", fake.last.method)
        assertTrue(fake.last.url.endsWith("/api/v1/books/b1/read-progress"))
    }
}
