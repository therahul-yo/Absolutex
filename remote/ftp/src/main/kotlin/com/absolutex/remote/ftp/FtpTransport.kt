package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Minimal random-read surface the FTP lane needs: a size probe plus exact range reads.
 *
 * FTP has no random-read primitive — every [readAt] costs one REST+RETR round trip — so callers
 * batch through the shared streaming stack, which coalesces contiguous ranges and caches
 * blocks. All methods block on the network; call off the main thread.
 */
interface FtpTransport {
    /** Total file size in bytes. */
    @Throws(IOException::class)
    fun sizeBytes(path: String): Long

    /** Exactly [length] bytes from [offset], or throws [IOException] on a short read. */
    @Throws(IOException::class)
    fun readAt(path: String, offset: Long, length: Int): ByteArray
}

/**
 * Binds this path-based transport to one file for the shared streaming stack: the same
 * [com.absolutex.remote.core.SeekableReader] serves SMB and FTP once each path has an adapter.
 */
fun FtpTransport.bind(path: String): com.absolutex.remote.core.RangeTransport =
    object : com.absolutex.remote.core.RangeTransport {
        override fun sizeBytes(): Long = this@bind.sizeBytes(path)

        override fun readAt(offset: Long, length: Int): ByteArray =
            this@bind.readAt(path, offset, length)
    }
