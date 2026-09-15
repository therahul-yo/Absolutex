package com.absolutex.remote.ftp

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FtpSeekableReaderTest {

    private val bytes = ByteArray(600_000) { (it % 251).toByte() }
    private val path = "/b.cbz"

    private fun reader(fake: FakeFtpTransport = FakeFtpTransport(bytes)): FtpSeekableReader =
        FtpSeekableReader(fake, path, bytes.size.toLong())

    @Test fun `read returns exact bytes across block boundaries`() {
        val live = reader()
        assertArrayEquals(bytes.copyOfRange(65_000, 66_000), live.read(65_000, 1000))
    }

    @Test fun `contiguous missing blocks coalesce into one round trip`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        val got = live.read(1000, 200_000)
        assertArrayEquals(bytes.copyOfRange(1000, 201_000), got)
        assertEquals(1, fake.readCalls)
    }

    @Test fun `cached repeat costs no new round trip`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        live.read(1000, 200_000)
        live.read(5000, 100_000)
        assertEquals(1, fake.readCalls)
    }

    @Test fun `partial cache fetches only the missing runs`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        val block = FtpBlockCache.BLOCK_SIZE.toLong()
        live.read(5 * block, 100)
        val before = fake.readCalls
        val got = live.read(4 * block, (3 * block).toInt())
        assertArrayEquals(bytes.copyOfRange((4 * block).toInt(), (7 * block).toInt()), got)
        assertEquals(2, fake.readCalls - before)
    }

    @Test fun `counters accumulate fetched bytes and calls`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        val block = FtpBlockCache.BLOCK_SIZE.toLong()
        live.read(0, block.toInt())
        live.read(3 * block, block.toInt())
        assertEquals(2, live.readCalls)
        assertEquals(2 * block, live.bytesFetched)
    }

    @Test fun `read past the end degrades to IOException`() {
        val live = reader()
        var thrown: IOException? = null
        try {
            live.read(bytes.size - 10L, 20)
        } catch (expected: IOException) {
            thrown = expected
        }
        assertNotNull(thrown)
    }

    @Test fun `zero-length read returns empty with no fetch`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        assertEquals(0, live.read(1234, 0).size)
        assertEquals(0, fake.readCalls)
    }

    @Test fun `read at end of file returns empty with no fetch`() {
        val fake = FakeFtpTransport(bytes)
        val live = reader(fake)
        assertEquals(0, live.read(bytes.size.toLong(), 0).size)
        assertEquals(0, fake.readCalls)
    }
}
