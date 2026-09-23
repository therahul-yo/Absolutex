package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Minimal random-read surface the FTP lane needs: a size probe plus exact range reads.
 *
 * FTP has no random-read primitive — every [readAt] costs one REST+RETR round trip — so callers
 * batch through the shared streaming stack, which coalesces contiguous ranges and caches
 * blocks. All methods block on the network; call off the main thread.
 */
interface FtpTransport : AutoCloseable {
    /** Total file size in bytes. */
    @Throws(IOException::class)
    fun sizeBytes(path: String): Long

    /** Exactly [length] bytes from [offset], or throws [IOException] on a short read. */
    @Throws(IOException::class)
    fun readAt(path: String, offset: Long, length: Int): ByteArray

    /**
     * Entries of the directory at [path]: subfolders plus files. Names are base names for
     * display and matching; the folder browser filters them against
     * `LibraryScanner.CONTAINER_EXTENSIONS`. A missing path throws [IOException].
     */
    @Throws(IOException::class)
    fun listDir(path: String): List<FtpEntry>
}

/** One remote directory entry: a subfolder, or a file with its size in bytes. */
data class FtpEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

/**
 * Binds this path-based transport to one file for the shared streaming stack: the same
 * [com.absolutex.remote.core.SeekableReader] serves SMB and FTP once each path has an adapter.
 *
 * The bound file also forwards [com.absolutex.remote.core.TransportInvalidator] when the
 * delegate implements it (CommonsNetFtpTransport does), so network-change monitoring
 * reaches the live session through the file handle the book owns.
 */
fun FtpTransport.bind(path: String): com.absolutex.remote.core.RangeTransport {
    val invalidator = this as? com.absolutex.remote.core.TransportInvalidator
    return object : com.absolutex.remote.core.RangeTransport,
        com.absolutex.remote.core.TransportInvalidator {
        override fun sizeBytes(): Long = this@bind.sizeBytes(path)

        override fun readAt(offset: Long, length: Int): ByteArray =
            this@bind.readAt(path, offset, length)

        // Forwarded: the opener closes the bound file, and the session must die with it.
        override fun close() = this@bind.close()

        override fun invalidate() {
            invalidator?.invalidate()
        }
    }
}
