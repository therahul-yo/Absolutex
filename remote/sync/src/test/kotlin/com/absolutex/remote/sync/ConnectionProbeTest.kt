package com.absolutex.remote.sync

import com.absolutex.remote.core.HttpUrlConnectionCall
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connection probes against a real in-process HTTP server. Each behaviour maps to exactly
 * one [ConnectionResult] variant — and no variant ever carries a message, banner, path or
 * credential (there is nothing to assert on that: the type has no message field by design).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionProbeTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()

    private fun komgaProbe() = KomgaConnectionProbe(HttpUrlConnectionCall())

    private fun komgaAuth() = KomgaAuth.Basic("u", "pw".toCharArray())

    private fun komgaBase() = server.url("/").toString().trimEnd('/')

    @Test fun `komga reachable and speaking the protocol is ok`() = runTest {
        server.enqueue(respond(200, "{\"content\":[{\"id\":\"s1\",\"name\":\"S\"}],\"last\":true}"))
        assertEquals(ConnectionResult.Ok, komgaProbe().test(komgaBase(), komgaAuth()))
    }

    @Test fun `komga 401 is auth failed`() = runTest {
        server.enqueue(respond(401))
        assertEquals(ConnectionResult.AuthFailed, komgaProbe().test(komgaBase(), komgaAuth()))
    }

    @Test fun `komga 404 is not found`() = runTest {
        server.enqueue(respond(404))
        assertEquals(ConnectionResult.NotFound, komgaProbe().test(komgaBase(), komgaAuth()))
    }

    @Test fun `komga 200 with a foreign body is not found`() = runTest {
        server.enqueue(respond(200, "<html>not komga</html>"))
        assertEquals(ConnectionResult.NotFound, komgaProbe().test(komgaBase(), komgaAuth()))
    }

    @Test fun `komga down server is unreachable`() = runTest {
        server.close()
        assertEquals(ConnectionResult.Unreachable, komgaProbe().test(komgaBase(), komgaAuth()))
    }

    private fun kavitaProbe() = KavitaConnectionProbe(HttpUrlConnectionCall())

    private fun login() = KavitaConnectionProbe.Credentials.Login("alice", "s3cret".toCharArray())

    @Test fun `kavita login plus libraries is ok`() = runTest {
        server.enqueue(respond(200, "{\"token\":\"t\",\"refreshToken\":\"r\"}"))
        server.enqueue(respond(200, "[{\"id\":1,\"name\":\"L\"}]"))
        assertEquals(ConnectionResult.Ok, kavitaProbe().test(komgaBase(), login()))
    }

    @Test fun `kavita rejected login is auth failed`() = runTest {
        server.enqueue(respond(401))
        assertEquals(ConnectionResult.AuthFailed, kavitaProbe().test(komgaBase(), login()))
    }

    @Test fun `kavita api key exchanges then probes`() = runTest {
        server.enqueue(respond(200, "{\"token\":\"jwt-k\",\"refreshToken\":\"r-k\"}"))
        server.enqueue(respond(200, "[{\"id\":1,\"name\":\"L\"}]"))
        val credentials = KavitaConnectionProbe.Credentials.ApiKey("raw-key".toCharArray())
        assertEquals(ConnectionResult.Ok, kavitaProbe().test(komgaBase(), credentials))
        val exchange = server.takeRequest()
        assertTrue(exchange.target.orEmpty().contains("/api/Plugin/authenticate"))
        val libraries = server.takeRequest()
        assertTrue(libraries.target.orEmpty().endsWith("/api/Library/libraries"))
        assertEquals("Bearer jwt-k", libraries.headers["Authorization"])
    }

    @Test fun `kavita rejected api key is auth failed`() = runTest {
        server.enqueue(respond(401))
        val credentials = KavitaConnectionProbe.Credentials.ApiKey("bad".toCharArray())
        assertEquals(ConnectionResult.AuthFailed, kavitaProbe().test(komgaBase(), credentials))
    }

    @Test fun `kavita down server is unreachable`() = runTest {
        server.close()
        assertEquals(ConnectionResult.Unreachable, kavitaProbe().test(komgaBase(), login()))
    }
}
