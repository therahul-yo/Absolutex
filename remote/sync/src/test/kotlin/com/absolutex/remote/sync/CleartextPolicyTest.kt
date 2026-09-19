package com.absolutex.remote.sync

import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpResponse
import com.absolutex.remote.core.withCleartextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The per-request scheme rule and manual redirect following, over a scripted transport. */
class CleartextPolicyTest {

    private fun policy(allowCleartext: Boolean, fake: FakeHttpCall = FakeHttpCall()): HttpCall =
        fake.withCleartextPolicy(allowCleartext)

    private fun redirectTo(target: String) = mapOf("Location" to listOf(target))

    @Test fun `http without the opt-in makes no request`() {
        val fake = FakeHttpCall()
        try {
            policy(false, fake).request("GET", "http://nas:8080/api/x", emptyMap(), null)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not allowed") == true)
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun `http with the opt-in proceeds`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(200, "{}"))
        val response = policy(true, fake).request("GET", "http://nas:8080/api/x", emptyMap(), null)
        assertEquals(200, response.code)
        assertEquals(1, fake.requests.size)
    }

    @Test fun `https redirected to http without the opt-in makes no second request`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(301, "", null, redirectTo("http://nas:8080/api/x")))
        try {
            policy(false, fake).request("GET", "https://nas:8443/api/x", emptyMap(), null)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("not allowed") == true)
        }
        assertEquals(1, fake.requests.size)
    }

    @Test fun `https redirected to http with the opt-in follows`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(301, "", null, redirectTo("http://nas:8080/api/x")))
        fake.enqueue(HttpResponse(200, "{\"ok\":true}"))
        val response = policy(true, fake).request("GET", "https://nas:8443/api/x", emptyMap(), null)
        assertEquals(200, response.code)
        assertEquals("{\"ok\":true}", response.body)
        assertEquals(2, fake.requests.size)
        assertEquals("http://nas:8080/api/x", fake.requests[1].url)
    }

    @Test fun `cross-host redirect strips auth headers`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(302, "", null, redirectTo("https://cdn.example.com/api/x")))
        fake.enqueue(HttpResponse(200, "{}"))
        val headers = mapOf("Authorization" to "Bearer secret", "X-API-Key" to "key")
        policy(false, fake).request("GET", "https://nas:8443/api/x", headers, null)
        assertEquals(2, fake.requests.size)
        assertTrue(fake.requests[0].headers.containsKey("Authorization"))
        assertTrue(!fake.requests[1].headers.containsKey("Authorization"))
        assertTrue(!fake.requests[1].headers.containsKey("X-API-Key"))
    }

    @Test fun `same-host redirect keeps auth headers`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(302, "", null, redirectTo("https://nas:8443/api/y")))
        fake.enqueue(HttpResponse(200, "{}"))
        val headers = mapOf("Authorization" to "Bearer secret")
        policy(false, fake).request("GET", "https://nas:8443/api/x", headers, null)
        assertEquals("Bearer secret", fake.requests[1].headers["Authorization"])
    }

    @Test fun `post converts to get without body on 301 but not 307`() {
        val fake = FakeHttpCall()
        fake.enqueue(HttpResponse(301, "", null, redirectTo("https://nas:8443/api/y")))
        fake.enqueue(HttpResponse(200, "{}"))
        policy(false, fake).request("POST", "https://nas:8443/api/x", emptyMap(), "{\"a\":1}")
        assertEquals("GET", fake.requests[1].method)
        assertEquals(null, fake.requests[1].body)

        val second = FakeHttpCall()
        second.enqueue(HttpResponse(307, "", null, redirectTo("https://nas:8443/api/y")))
        second.enqueue(HttpResponse(200, "{}"))
        policy(false, second).request("POST", "https://nas:8443/api/x", emptyMap(), "{\"a\":1}")
        assertEquals("POST", second.requests[1].method)
        assertEquals("{\"a\":1}", second.requests[1].body)
    }

    @Test fun `redirect loop stops at the hop cap`() {
        val fake = FakeHttpCall()
        repeat(10) {
            fake.enqueue(HttpResponse(302, "", null, redirectTo("https://nas:8443/api/loop")))
        }
        val response = policy(false, fake).request("GET", "https://nas:8443/api/loop", emptyMap(), null)
        assertEquals(302, response.code)
        assertEquals(6, fake.requests.size)
    }
}
