package com.absolutex.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ZipRemoteIndexTest {

    @Test fun `lists deflated entries with data ranges`() {
        val archive = ZipFixtures.cbz(
            "page02.jpg" to ZipFixtures.pageBytes(2),
            "page10.jpg" to ZipFixtures.pageBytes(10),
        )
        val reader = SeekableSmbReader(FakeSmbTransport(mapOf("b.cbz" to archive)), "b.cbz", archive.size.toLong())
        val index = ZipRemoteIndex.open(reader)
        assertEquals(2, index.entries.size)
        assertTrue(index.entries.all { it.dataOffset > 0 })
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
}
