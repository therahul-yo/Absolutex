package com.absolutex.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Hostile-ZIP suite: every way a remote byte stream stops being an archive must end in
 * IOException with a message, never in an unchecked crash. Each test names the exact hostile
 * input so a regression reads as what it is.
 */
class ZipDirectoryTest {

    private fun openOf(bytes: ByteArray): List<ZipDirectory.Entry> {
        val transport = FakeRangeTransport(bytes)
        return ZipDirectory.open(transport::readAt, bytes.size.toLong())
    }

    @Test fun `valid entries list with ranges`() {
        val archive = ZipBytes.cbz(
            "page02.jpg" to ZipBytes.pageBytes(2),
            "page10.jpg" to ZipBytes.pageBytes(10),
        )
        val entries = openOf(archive)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.localHeaderOffset >= 0 })
    }

    @Test fun `truncation degrades to IOException`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val cut = archive.copyOf(archive.size / 2)
        try {
            openOf(cut)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected is IOException)
        }
    }

    @Test fun `signature inside the comment is not the EOCD`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val eocdAt = ZipBytes.eocdStart(archive)
        val fakeComment = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0x7F, 0x7F, 0x7F, 0x7F)
        val out = archive.copyOf(archive.size + fakeComment.size)
        ZipBytes.le16(out, eocdAt + 20, fakeComment.size)
        fakeComment.copyInto(out, archive.size)
        val entries = openOf(out)
        assertEquals(1, entries.size)
        assertEquals("page01.jpg", entries[0].name)
    }

    @Test fun `signature with room after it is rejected by length, not bounds`() {
        // The fake above sits 8 bytes from EOF, so the 22-byte bounds check rejects it first
        // and the comment-to-end equality never runs. Here the fake has 28 bytes after it
        // inside a 32-byte comment — bounds pass, servable flavour passes, and only the
        // length check can reject it.
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val eocdAt = ZipBytes.eocdStart(archive)
        val comment = ByteArray(32)
        comment[0] = 0x50
        comment[1] = 0x4B
        comment[2] = 0x05
        comment[3] = 0x06
        comment[8] = 1
        comment[10] = 1
        comment[12] = 100
        comment[16] = 200.toByte()
        val out = archive.copyOf(archive.size + comment.size)
        ZipBytes.le16(out, eocdAt + 20, comment.size)
        comment.copyInto(out, archive.size)
        val entries = openOf(out)
        assertEquals(1, entries.size)
        assertEquals("page01.jpg", entries[0].name)
    }

    @Test fun `oversized central directory is rejected before transfer`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val patched = archive.copyOf()
        ZipBytes.le32(patched, ZipBytes.eocdStart(patched) + 12, 40_000_000L)
        ZipBytes.le32(patched, ZipBytes.eocdStart(patched) + 16, 0L)
        val transport = FakeRangeTransport(patched)
        try {
            ZipDirectory.open(transport::readAt, patched.size.toLong())
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("too large") == true)
        }
        assertTrue("must not transfer the directory", transport.bytesServed < 256 * 1024)
    }

    @Test fun `zip64 entry-count sentinel degrades to IOException`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val patched = archive.copyOf()
        ZipBytes.le16(patched, ZipBytes.eocdStart(patched) + 10, 0xFFFF)
        try {
            openOf(patched)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `eight-byte half-signature degrades to IOException`() {
        // A local header followed by half an EOCD: bounds must fail before any field is read.
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x50, 0x4B, 0x05, 0x06)
        try {
            openOf(bytes)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `untrusted name length degrades to IOException`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1))
        val patched = archive.copyOf()
        val cdOffset = ZipBytes.u32(patched, ZipBytes.eocdStart(patched) + 16).toInt()
        ZipBytes.le16(patched, cdOffset + 28, 0x4000)
        try {
            openOf(patched)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("past end") == true)
        }
    }

    @Test fun `non-zip bytes rejected with message`() {
        val bytes = ByteArray(64) { 7 }
        try {
            openOf(bytes)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("end-of-central-directory") == true)
        }
    }

    @Test fun `local data offsets resolve past the headers`() {
        val archive = ZipBytes.cbz("page01.jpg" to ZipBytes.pageBytes(1, 2048), stored = true)
        val transport = FakeRangeTransport(archive)
        val entries = ZipDirectory.open(transport::readAt, archive.size.toLong())
        val data = ZipDirectory.localDataOffsetOf(transport::readAt, entries[0])
        assertTrue(data > entries[0].localHeaderOffset)
        val bytes = transport.readAt(data, 2048)
        assertTrue(bytes.contentEquals(ZipBytes.pageBytes(1, 2048)))
    }
}
