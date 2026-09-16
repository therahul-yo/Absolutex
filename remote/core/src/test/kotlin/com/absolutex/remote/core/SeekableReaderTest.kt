package com.absolutex.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SeekableReaderTest {

    private fun readerOf(size: Int, blockSize: Int = 16): Pair<FakeRangeTransport, SeekableReader> {
        val bytes = ByteArray(size) { it.toByte() }
        val transport = FakeRangeTransport(bytes)
        val reader = SeekableReader(transport, size.toLong(), BlockCache(blockSize, 1024))
        return transport to reader
    }

    @Test fun `read returns exact range`() {
        val (_, reader) = readerOf(64)
        assertTrue(reader.readAt(10, 6).contentEquals(byteArrayOf(10, 11, 12, 13, 14, 15)))
    }

    @Test fun `repeat read hits cache with no new traffic`() {
        val (transport, reader) = readerOf(64)
        reader.readAt(0, 16)
        val served = transport.bytesServed
        reader.readAt(0, 16)
        assertEquals(served, transport.bytesServed)
    }

    @Test fun `contiguous missing blocks coalesce into one round trip`() {
        val (_, reader) = readerOf(64)
        reader.readAt(0, 48)
        assertEquals(1, reader.readCalls)
    }

    @Test fun `straddling read fetches both blocks once`() {
        val (_, reader) = readerOf(64)
        reader.readAt(12, 8)
        assertEquals(1, reader.readCalls)
        assertTrue(reader.readAt(12, 8).contentEquals(byteArrayOf(12, 13, 14, 15, 16, 17, 18, 19)))
    }

    @Test fun `partial cache fetches only the missing runs`() {
        val (transport, reader) = readerOf(64)
        reader.readAt(0, 16)
        val calls = reader.readCalls
        reader.readAt(8, 16)
        assertEquals(calls + 1, reader.readCalls)
        assertTrue(transport.bytesServed <= 32)
    }

    @Test fun `read past end throws, never wraps`() {
        val (_, reader) = readerOf(32)
        try {
            reader.readAt(30, 4)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("past end") == true)
        }
    }

    @Test fun `stream reads entry bytes without the whole file`() {
        val (transport, reader) = readerOf(256)
        val bytes = reader.openStream(200, 20).use { it.readBytes() }
        assertEquals(20, bytes.size)
        assertEquals(200.toByte(), bytes[0])
        assertTrue(transport.bytesServed < 256)
    }

    @Test fun `read larger than the cache streams window by window`() {
        // A span the cache cannot hold used to fail with "cache miss after fetch" after
        // transferring the whole span. Oversize spans bypass the cache and assemble
        // straight from transport buffers instead.
        val size = 10 * 1024
        val bytes = ByteArray(size) { it.toByte() }
        val transport = FakeRangeTransport(bytes)
        val reader = SeekableReader(transport, size.toLong(), BlockCache(1024, 4096))
        assertTrue(reader.readAt(0, size).contentEquals(bytes))
    }

    @Test fun `non-aligned read at the cap bypasses the cache instead of evicting itself`() {
        // The faithful repro: offset 100 spills a cap-sized read over one block more than
        // the cache holds (5 blocks > 4), so any cached path evicts its own head.
        // Block-aligned offset 0 with the same length fits and stays cached.
        val size = 8 * 1024
        val bytes = ByteArray(size) { it.toByte() }
        val transport = FakeRangeTransport(bytes)
        val reader = SeekableReader(transport, size.toLong(), BlockCache(1024, 4096))
        assertTrue(reader.readAt(100, 4096).contentEquals(bytes.copyOfRange(100, 4196)))
        assertEquals(1, transport.ranges.size)
    }

    @Test fun `zero-length read returns empty with no fetch`() {
        val (transport, reader) = readerOf(64)
        assertEquals(0, reader.readAt(10, 0).size)
        assertEquals(0, transport.ranges.size)
    }
}
