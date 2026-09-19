package com.absolutex.remote.smb

import java.io.IOException

/**
 * Ranged reads against one remote file. Offset-based, like pread: concurrent calls are
 * independent, which is what lets the decode pool fan pages out without sharing offsets —
 * the same reason LibArchiveSource takes a descriptor factory instead of a descriptor.
 */
interface SmbTransport : AutoCloseable {
    /** Remote file size in bytes. Needed up front: ZIP parsing starts from the tail. */
    @Throws(IOException::class)
    fun sizeBytes(remotePath: String): Long

    /** Exactly [length] bytes from [offset], or IOException on short read. Never partial. */
    @Throws(IOException::class)
    fun readAt(remotePath: String, offset: Long, length: Int): ByteArray
}

/**
 * Establishes one authenticated share. Whatever it opens, it closes on failure — a failed
 * logon must not leak the client, socket and reader thread.
 */
interface SmbConnector {
    @Throws(IOException::class)
    fun connect(password: CharArray): SmbConnection
}

/** One open share. The transport opens one file handle per read; handles never escape it. */
interface SmbConnection : AutoCloseable {
    @Throws(IOException::class)
    fun openFile(remotePath: String): RemoteFileHandle
}

/** A single open remote file: length plus pread-style reads. */
interface RemoteFileHandle : AutoCloseable {
    val length: Long

    /**
     * Up to [length] bytes from [fileOffset] into [buffer] at [bufferOffset]; -1 or 0 only
     * at end of file, like InputStream.read.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int
}

/**
 * Binds this path-based transport to one file for the shared streaming stack: the same
 * [SeekableReader] serves SMB and FTP once each path has an adapter.
 *
 * The bound file also forwards [TransportInvalidator] when the delegate implements it
 * (SmbjTransport does), so network-change monitoring reaches the live session through
 * the file handle the book owns. Transports without invalidation simply no-op.
 */
fun SmbTransport.bind(remotePath: String): com.absolutex.remote.core.RangeTransport {
    val invalidator = this as? com.absolutex.remote.core.TransportInvalidator
    return object : com.absolutex.remote.core.RangeTransport,
        com.absolutex.remote.core.TransportInvalidator {
        override fun sizeBytes(): Long = this@bind.sizeBytes(remotePath)

        override fun readAt(offset: Long, length: Int): ByteArray =
            this@bind.readAt(remotePath, offset, length)

        // Forwarded: the opener closes the bound file, and the session must die with it.
        override fun close() = this@bind.close()

        override fun invalidate() {
            invalidator?.invalidate()
        }
    }
}
