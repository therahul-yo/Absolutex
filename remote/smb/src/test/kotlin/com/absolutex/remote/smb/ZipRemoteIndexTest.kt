package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ZipRemoteIndexTest {

    @Test fun `lists deflated entries with local header offsets`() {
        val archive = ZipFixtures.cbz(
            "page02.jpg" to ZipFixtures.pageBytes(2),
            "page10.jpg" to ZipFixtures.pageBytes(10),
        )
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to archive)), "b.cbz", archive.size.toLong())
        val index = ZipRemoteIndex.open(reader)
        assertEquals(2, index.entries.size)
        assertTrue(index.entries.all { it.localHeaderOffset >= 0 })
        assertTrue(index.entries.all { it.method == ZipRemoteIndex.METHOD_DEFLATED })
    }

    @Test fun `stored entries keep method and ranges`() {
        val archive = ZipFixtures.cbz("page01.png" to ZipFixtures.pageBytes(1), stored = true)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to archive)), "b.cbz", archive.size.toLong())
        val index = ZipRemoteIndex.open(reader)
        assertEquals(1, index.entries.size)
        assertEquals(ZipRemoteIndex.METHOD_STORED, index.entries[0].method)
    }

    @Test fun `non-zip bytes rejected with message`() {
        val bytes = ByteArray(64) { 7 }
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b" to bytes)), "b", bytes.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `truncated archive degrades to IOException, never crash`() {
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val cut = archive.copyOf(archive.size / 2)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to cut)), "b.cbz", cut.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected is IOException)
        }
    }

    @Test fun `eight-byte half-signature degrades to IOException, never ArrayIndexOutOfBounds`() {
        // Item 1 repro, byte for byte: a local header followed by half an EOCD. parseEocd
        // used to read base+4..base+19 unchecked past the 8-byte tail.
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x50, 0x4B, 0x05, 0x06)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b" to bytes)), "b", bytes.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `signature inside the comment is not the EOCD`() {
        // Real EOCD first, then a comment with an embedded fake signature: the backwards scan
        // meets the fake first and must reject it (comment would not run to end of file).
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val eocdAt = archive.size - 22
        val fakeComment = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0x7F, 0x7F, 0x7F, 0x7F)
        val out = archive.copyOf(archive.size + fakeComment.size)
        le16(out, eocdAt + 20, fakeComment.size)
        fakeComment.copyInto(out, archive.size)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to out)), "b.cbz", out.size.toLong())
        val index = ZipRemoteIndex.open(reader)
        assertEquals(1, index.entries.size)
        assertEquals("page01.jpg", index.entries[0].name)
    }

    @Test fun `signature with room after it is rejected by length, not bounds`() {
        // Gap in the old coverage: the fake above sits 8 bytes from EOF, so the 22-byte
        // bounds check rejects it first and the comment-to-end equality never runs. Here the
        // fake has 28 bytes after it inside a 32-byte comment — bounds pass, flavour passes
        // (zeroed disk fields), and only the length check can reject it.
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val eocdAt = archive.size - 22
        val comment = ByteArray(32)
        comment[0] = 0x50
        comment[1] = 0x4B
        comment[2] = 0x05
        comment[3] = 0x06
        // Disk fields zero (servable flavour), counts/sizes non-sentinel, comment length zero
        // so base + 22 + 0 != size can only fail the equality.
        comment[8] = 1
        comment[10] = 1
        comment[12] = 100
        comment[16] = 200.toByte()
        val out = archive.copyOf(archive.size + comment.size)
        le16(out, eocdAt + 20, comment.size)
        comment.copyInto(out, archive.size)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to out)), "b.cbz", out.size.toLong())
        val index = ZipRemoteIndex.open(reader)
        assertEquals(1, index.entries.size)
        assertEquals("page01.jpg", index.entries[0].name)
    }

    @Test fun `untrusted name length degrades to IOException, never StringIndexOutOfBounds`() {
        // Item 2 repro: a central directory claiming a 16 KiB name in a ~100-byte directory.
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val patched = archive.copyOf()
        val cdOffset = u32(patched, patched.size - 22 + 16).toInt()
        le16(patched, cdOffset + 28, 0x4000)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to patched)), "b.cbz", patched.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("past end") == true)
        }
    }

    @Test fun `zip64 entry-count sentinel degrades to IOException`() {
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val patched = archive.copyOf()
        le16(patched, patched.size - 22 + 8, 0xFFFF)
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to patched)), "b.cbz", patched.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `oversized central directory is rejected before transfer`() {
        // Item 3 repro: cdSize between the 32 MiB cache and the old 256 MiB cap must fail
        // on the numbers, not after an OOM-sized transfer.
        val archive = ZipFixtures.cbz("page01.jpg" to ZipFixtures.pageBytes(1))
        val patched = archive.copyOf()
        le32(patched, patched.size - 22 + 12, 40_000_000L)
        le32(patched, patched.size - 22 + 16, 0L)
        val transport = FakeSmbTransport(mapOf("b.cbz" to patched))
        val reader = SeekableSmbReader(transport, "b.cbz", patched.size.toLong())
        try {
            ZipRemoteIndex.open(reader)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("too large") == true)
        }
        assertTrue("must not transfer the directory", transport.bytesServed < 256 * 1024)
    }

    @Test fun `open costs tail plus directory, never a read per entry`() {
        // Item 6 traffic proof: local header offsets resolve on openPage, not on open.
        val archive = ZipFixtures.cbz(
            "page01.jpg" to ZipFixtures.pageBytes(1, 2048),
            "page02.jpg" to ZipFixtures.pageBytes(2, 2048),
            ZipFixtures.paddingEntry(padBytes = 256 * 1024),
            storedNames = setOf("preview.dat"),
        )
        val transport = FakeSmbTransport(mapOf("b.cbz" to archive))
        val reader = SeekableSmbReader(transport, "b.cbz", archive.size.toLong())
        ZipRemoteIndex.open(reader)
        assertTrue("open took ${transport.ranges.size} round trips", transport.ranges.size <= 3)
        assertTrue(
            "open must not touch local headers",
            transport.ranges.none { it.length == LOCAL_HEADER_READ },
        )
    }

    companion object {
        private const val LOCAL_HEADER_READ = 30

        private fun le16(bytes: ByteArray, at: Int, v: Int) {
            bytes[at] = v.toByte()
            bytes[at + 1] = (v shr 8).toByte()
        }

        private fun le32(bytes: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) {
                bytes[at + i] = (v shr (8 * i)).toByte()
            }
        }

        private fun u32(bytes: ByteArray, at: Int): Long =
            (bytes[at].toInt() and 0xFF).toLong() or
                ((bytes[at + 1].toInt() and 0xFF).toLong() shl 8) or
                ((bytes[at + 2].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[at + 3].toInt() and 0xFF).toLong() shl 24)
    }
}
