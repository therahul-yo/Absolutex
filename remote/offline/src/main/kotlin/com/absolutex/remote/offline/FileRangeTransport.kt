package com.absolutex.remote.offline

import com.absolutex.remote.core.RangeTransport
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * [RangeTransport] over a local file — the reading half of an offline copy.
 *
 * A finished copy opens through exactly the same path a streamed book does: `CoreComicSource`
 * asks a transport for byte ranges and neither knows nor cares whether they came off a socket
 * or a disk. That is why offline support is a transport rather than a second opener — one
 * archive parser, one page pipeline, one set of bugs.
 *
 * **Positional reads, and no descriptor is ever duplicated.** `FileChannel.read(buffer,
 * position)` leaves the channel's own position untouched, so concurrent [readAt] calls stay
 * independent exactly as the interface promises, with no shared offset to corrupt. One channel
 * is opened here, owned here, and closed here; nothing hands a file descriptor out.
 */
class FileRangeTransport(file: File) : RangeTransport {

    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    override fun sizeBytes(): Long = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "negative offset: $offset" }
        require(length >= 0) { "negative length: $length" }
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var at = offset
        while (buffer.hasRemaining()) {
            // Exact-or-throw, like every RangeTransport: a page half-filled from a truncated
            // copy would decode as corruption somewhere far from here.
            val read = channel.read(buffer, at)
            if (read < 0) {
                throw EOFException("short read: ${buffer.position()} of $length bytes at $offset")
            }
            at += read
        }
        return buffer.array()
    }

    override fun close() {
        channel.close()
    }
}
