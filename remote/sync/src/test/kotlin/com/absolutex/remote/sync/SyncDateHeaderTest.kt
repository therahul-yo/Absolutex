package com.absolutex.remote.sync

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The server clock is observed through the HTTP `Date` header: this pins the header parsing
 * against a real server, so the skew correction in [ServerClock] measures a real clock.
 */
class SyncDateHeaderTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun rfc1123(epochMs: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    @Test fun `server date header parses through real http`() {
        val date = 1_787_280_000_000L
        server.enqueue(
            MockResponse.Builder().code(200).body("{}").addHeader(DATE_HEADER, rfc1123(date)).build(),
        )
        val response = HttpUrlConnectionCall().request("GET", server.url("/x").toString(), emptyMap(), null)
        assertEquals(date, response.serverDateMs)
    }

    @Test fun `missing date reads as unknown`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val response = HttpUrlConnectionCall().request("GET", server.url("/x").toString(), emptyMap(), null)
        assertNull(response.serverDateMs)
    }
}
