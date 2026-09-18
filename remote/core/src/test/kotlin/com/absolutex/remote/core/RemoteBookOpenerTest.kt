package com.absolutex.remote.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Uri parsing and backend dispatch — no network shape beyond the transport seam. */
class RemoteBookOpenerTest {

    @Test fun `parses server path and file name`() {
        val location = parseRemoteUri("absolutex-remote://nas/comics/book.cbz")
        assertEquals(RemoteLocation("nas", "/comics/book.cbz", "book.cbz"), location)
    }

    @Test fun `rejects foreign schemes and empty parts`() {
        for (uri in listOf(
            "https://nas/comics/book.cbz",
            "absolutex-remote://",
            "absolutex-remote://nas/",
            "absolutex-remote://nas",
            "not a uri at all",
        )) {
            try {
                parseRemoteUri(uri)
                fail("expected IllegalArgumentException for $uri")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("remote uri") == true)
            }
        }
    }

    @Test fun `encoded names decode like BookPath`() = runTest {
        val location = parseRemoteUri("absolutex-remote://nas/comics/my%20book%2Fv2.cbz")
        assertEquals(RemoteLocation("nas", "/comics/my book/v2.cbz", "v2.cbz"), location)
    }

    @Test fun `dispatcher hands the file to the backend transport`() = runTest {
        val bytes = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        var seenServer = ""
        var seenPath = ""
        val opener = TransportBookOpener { serverId, path ->
            seenServer = serverId
            seenPath = path
            FakeRangeTransport(bytes)
        }
        val result = opener.open("absolutex-remote://nas/comics/book.cbz")
        assertEquals("nas", seenServer)
        assertEquals("/comics/book.cbz", seenPath)
        assertTrue(result is RemoteOpenResult.Ready)
        val ready = result as RemoteOpenResult.Ready
        assertEquals(1, ready.source.pages.size)
        assertEquals("book.cbz", ready.displayName)
        ready.source.close()
    }

    @Test fun `transport failure propagates as IOException, never download-required`() = runTest {
        val opener = TransportBookOpener { _, _ -> throw IOException("server down") }
        try {
            opener.open("absolutex-remote://nas/comics/book.cbz")
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("server down") == true)
        }
    }
}
