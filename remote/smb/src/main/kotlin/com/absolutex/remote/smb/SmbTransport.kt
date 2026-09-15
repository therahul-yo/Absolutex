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
