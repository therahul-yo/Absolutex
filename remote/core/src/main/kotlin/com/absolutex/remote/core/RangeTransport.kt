package com.absolutex.remote.core

import java.io.IOException

/**
 * Ranged reads against one remote file, bound at construction. Offset-based, like pread:
 * concurrent calls are independent, which is what lets the decode pool fan pages out without
 * sharing offsets. SMB and FTP adapters bind their path-based transports to one file each.
 */
interface RangeTransport {
    /** Remote file size in bytes. Needed up front: ZIP parsing starts from the tail. */
    @Throws(IOException::class)
    fun sizeBytes(): Long

    /** Exactly [length] bytes from [offset], or IOException on short read. Never partial. */
    @Throws(IOException::class)
    fun readAt(offset: Long, length: Int): ByteArray
}
