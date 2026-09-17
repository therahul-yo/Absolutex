package com.absolutex.remote.sync

import java.time.Instant
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
 * Kavita client tests over hand-written JSON matching the verified OpenAPI shapes (UserDto,
 * LibraryDto, SeriesDto, ProgressDto). Robolectric provides the real org.json on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KavitaClientTest {

    private fun client(fake: FakeHttpCall) = KavitaClient(fake, "http://lan:5000")

    private fun session(token: String, refresh: String) =
        "{\"username\":\"u\",\"token\":\"$token\",\"refreshToken\":\"$refresh\"}"

    private fun loggedIn(fake: FakeHttpCall): KavitaClient {
        fake.enqueue(HttpResponse(200, session("jwt-1", "r-1")))
        return client(fake).also { it.login("u", "pw".toCharArray()) }
    }

    @Test fun loginSendsCredentialsAndStoresBearer() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        val loginBody = JSONObject(fake.requests[0].body)
        assertEquals("u", loginBody.getString("username"))
        assertEquals("pw", loginBody.getString("password"))
        fake.enqueue(HttpResponse(200, "[]"))
        underTest.libraries()
        assertEquals("Bearer jwt-1", fake.last.headers["Authorization"])
    }

    @Test fun unauthorizedRefreshesOnceThenRetries() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(401, ""))
        fake.enqueue(HttpResponse(200, session("jwt-2", "r-2")))
        fake.enqueue(HttpResponse(200, "[{\"id\":3,\"name\":\"Comics\"}]"))
        val libraries = underTest.libraries()
        assertEquals("Comics", libraries.single().name)
        assertEquals("Bearer jwt-2", fake.last.headers["Authorization"])
        assertTrue(fake.requests.any { it.url.endsWith("/api/Account/refresh-token") })
    }

    @Test fun secondUnauthorizedAfterRefreshFails() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(401, ""))
        fake.enqueue(HttpResponse(200, session("jwt-2", "r-2")))
        fake.enqueue(HttpResponse(401, ""))
        assertThrows(java.io.IOException::class.java) { underTest.libraries() }
    }

    @Test fun failedRefreshSurfaces() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(401, ""))
        fake.enqueue(HttpResponse(403, ""))
        assertThrows(java.io.IOException::class.java) { underTest.libraries() }
    }

    @Test fun progressParsesPageAndTimestamp() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(200, "{\"volumeId\":1,\"chapterId\":9,\"pageNum\":4," +
            "\"seriesId\":7,\"libraryId\":3,\"lastModifiedUtc\":\"2026-09-02T12:00:00Z\"}"))
        val progress = underTest.getProgress(9)
        assertEquals(4, progress?.page)
        assertEquals(Instant.parse("2026-09-02T12:00:00Z").toEpochMilli(), progress?.updatedAt)
    }

    @Test fun progressWithoutTimestampDegradesToEpochZero() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(200, "{\"volumeId\":1,\"chapterId\":9,\"pageNum\":4," +
            "\"seriesId\":7,\"libraryId\":3}"))
        assertEquals(0L, underTest.getProgress(9)?.updatedAt)
    }

    @Test fun saveProgressPostsDtoFields() {
        val fake = FakeHttpCall()
        val underTest = loggedIn(fake)
        fake.enqueue(HttpResponse(200, ""))
        underTest.saveProgress(KavitaProgress(volumeId = 1, chapterId = 9, pageNum = 4, seriesId = 7, libraryId = 3))
        assertEquals("POST", fake.last.method)
        assertTrue(fake.last.url.endsWith("/api/Reader/progress"))
        val body = JSONObject(fake.last.body)
        assertEquals(9, body.getInt("chapterId"))
        assertEquals(4, body.getInt("pageNum"))
        assertEquals(7, body.getInt("seriesId"))
    }

    @Test fun corruptLoginJsonFails() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, "{bad"))
        assertThrows(java.io.IOException::class.java) { client(fake).login("u", "pw".toCharArray()) }
    }

    @Test fun missingTokenFails() {
        val fake = FakeHttpCall()
        assertThrows(java.io.IOException::class.java) { client(fake).libraries() }
    }
}
