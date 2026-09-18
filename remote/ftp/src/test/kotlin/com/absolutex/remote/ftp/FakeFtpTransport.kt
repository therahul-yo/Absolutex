package com.absolutex.remote.ftp

import java.io.IOException

/** In-memory [FtpTransport]: exact slices of [bytes], with call recording for cost assertions. */
internal class FakeFtpTransport(
    val bytes: ByteArray,
    private val entries: List<FtpEntry> = emptyList(),
) : FtpTransport {

    var readCalls = 0
        private set
    var bytesServed = 0L
        private set
    val calls = mutableListOf<Pair<Long, Int>>()

    override fun sizeBytes(path: String): Long = bytes.size.toLong()

    override fun listDir(path: String): List<FtpEntry> = entries

    override fun readAt(path: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        readCalls++
        calls.add(offset to length)
        if (offset + length > bytes.size) throw IOException("short read: $path at $offset+$length")
        bytesServed += length.toLong()
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }
}
