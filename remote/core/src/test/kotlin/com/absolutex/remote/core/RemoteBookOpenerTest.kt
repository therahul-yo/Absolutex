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

    @Test fun `plus escapes survive, raw plus follows form rules`() = runTest {
        // %2B is an encoded plus and decodes to one; a raw + is form data and decodes
        // to a space — same contract as BookPath, so builders must encode literal plus.
        val encoded = parseRemoteUri("absolutex-remote://nas/Batman%20%2B%20Robin.cbz")
        assertEquals(RemoteLocation("nas", "/Batman + Robin.cbz", "Batman + Robin.cbz"), encoded)
        val raw = parseRemoteUri("absolutex-remote://nas/a+b.cbz")
        assertEquals(RemoteLocation("nas", "/a b.cbz", "a b.cbz"), raw)
    }

    @Test fun `non-ascii names decode from utf-8 escapes`() = runTest {
        val name = String(charArrayOf(0x6F2B.toChar(), 0x753B.toChar())) + "01.cbz"
        val location = parseRemoteUri("absolutex-remote://nas/%E6%BC%AB%E7%94%BB01.cbz")
        assertEquals(RemoteLocation("nas", "/$name", name), location)
    }

    @Test fun `encoder round-trips hostile names exactly`() {
        val paths = listOf(
            "/Batman + Robin.cbz",
            "/comics/my book/v2.cbz",
            "/a%2Fb.cbz",
            "/100% legit + (special).cbz",
            "/${String(charArrayOf(0x6F2B.toChar(), 0x753B.toChar()))}01.cbz",
            "/plain.cbz",
        )
        for (path in paths) {
            val uri = encodeRemoteUri("nas", path)
            val location = parseRemoteUri(uri)
            assertEquals(RemoteLocation("nas", path, path.substringAfterLast('/')), location)
        }
        // Spot-check the encoding itself: space is %20, plus is %2B, never a raw +.
        assertEquals(
            "absolutex-remote://nas/Batman%20%2B%20Robin.cbz",
            encodeRemoteUri("nas", "/Batman + Robin.cbz"),
        )
    }

    @Test fun `encoder rejects blank servers and relative paths`() {
        for ((id, path) in listOf(
            "" to "/b.cbz",
            "na/s" to "/b.cbz",
            "nas" to "b.cbz",
        )) {
            try {
                encodeRemoteUri(id, path)
                fail("expected IllegalArgumentException for $id $path")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.isNotEmpty() == true)
            }
        }
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
