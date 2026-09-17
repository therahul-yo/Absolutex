package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Minimal random-read surface the FTP lane needs: a size probe plus exact range reads.
 *
 * FTP has no random-read primitive — every [readAt] costs one REST+RETR round trip — so callers
 * batch through [FtpSeekableReader], which coalesces contiguous ranges and caches blocks. All
 * methods block on the network; call off the main thread.
 */
interface FtpTransport {
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
