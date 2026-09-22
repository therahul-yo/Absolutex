package com.absolutex.remote.offline

import com.absolutex.remote.core.RangeTransport

/** Deterministic content, so any splice or off-by-one shows up as a wrong byte at a known index. */
internal fun byteAt(index: Long): Byte = ((index * 31 + 7) % 251).toByte()

internal fun expectedBytes(size: Int, from: Long = 0): ByteArray =
    ByteArray(size) { byteAt(from + it) }

/** A remote file that never existed, counting what was actually pulled off it. */
internal class FakeSource(private val totalBytes: Long) : RangeTransport {
    var readCalls = 0
        private set
    var bytesRead = 0L
        private set
    var closed = false
        private set

    override fun sizeBytes(): Long = totalBytes

    override fun readAt(offset: Long, length: Int): ByteArray {
        readCalls++
        bytesRead += length.toLong()
        return ByteArray(length) { byteAt(offset + it) }
    }

    override fun close() {
        closed = true
    }
}

/** Refuses to be read at all — proves a path transferred nothing rather than merely little. */
internal class ForbiddenSource(private val totalBytes: Long) : RangeTransport {
    override fun sizeBytes(): Long = totalBytes
    override fun readAt(offset: Long, length: Int): ByteArray =
        throw AssertionError("readAt must not be called: $length bytes at $offset")
}
