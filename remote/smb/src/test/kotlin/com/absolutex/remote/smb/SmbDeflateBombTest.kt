package com.absolutex.remote.smb

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A ~1 MB DEFLATE entry inflates past 1 GB: without an output bound the page read OOMs
 * instead of failing. Zeros compress ~1000:1, so the fixtures stay small while the
 * inflated sizes are real.
 */
class SmbDeflateBombTest {

    @Test fun `declared output past the hard cap fails before inflation`() {
        val raw = ByteArray(4096)
        val archive = ZipFixtures.cbz("page01.jpg" to raw)
        // A lying directory declaring 2 GB of output for a 4 KB page.
        val patched = archive.copyOf()
        le32(patched, cdOffsetOf(patched) + CD_UNCOMP_SIZE, 0x50000000L)
        val transport = FakeSmbTransport(mapOf("book.cbz" to patched))
        SmbStreamingSource.open(transport, "book.cbz").use { source ->
            try {
                source.openPage(0).use { it.readBytes() }
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("inflated") == true)
            }
        }
    }

    @Test fun `lying small declared size aborts mid-stream`() {
        val raw = ByteArray(1024 * 1024)
        val archive = ZipFixtures.cbz("page01.jpg" to raw)
        // Directory claims 100 bytes; the stream really holds 1 MB of zeros.
        val patched = archive.copyOf()
        le32(patched, cdOffsetOf(patched) + CD_UNCOMP_SIZE, 100L)
        val transport = FakeSmbTransport(mapOf("book.cbz" to patched))
        SmbStreamingSource.open(transport, "book.cbz").use { source ->
            try {
                source.openPage(0).use { it.readBytes() }
                fail("expected IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("declared size") == true)
            }
        }
    }

    @Test fun `honest large page within the cap still reads`() {
        // 4 MB of zeros is a legitimate big page; the bound must not break it.
        val raw = ByteArray(4 * 1024 * 1024)
        val archive = ZipFixtures.cbz("page01.jpg" to raw)
        val transport = FakeSmbTransport(mapOf("book.cbz" to archive))
        SmbStreamingSource.open(transport, "book.cbz").use { source ->
            assertTrue(source.openPage(0).use { it.readBytes() }.contentEquals(raw))
        }
    }

    companion object {
        private const val CD_UNCOMP_SIZE = 24

        private fun cdOffsetOf(archive: ByteArray): Int {
            val at = archive.size - 22 + 16
            return ((archive[at].toInt() and 0xFF)) or
                ((archive[at + 1].toInt() and 0xFF) shl 8) or
                ((archive[at + 2].toInt() and 0xFF) shl 16) or
                ((archive[at + 3].toInt() and 0xFF) shl 24)
        }

        private fun le32(bytes: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) {
                bytes[at + i] = (v shr (8 * i)).toByte()
            }
        }
    }
}
