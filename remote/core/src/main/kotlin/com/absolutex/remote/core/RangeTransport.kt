package com.absolutex.remote.core

import java.io.IOException

/**
 * Ranged reads against one remote file, bound at construction. Offset-based, like pread:
 * concurrent calls are independent, which is what lets the decode pool fan pages out without
 * sharing offsets. SMB and FTP adapters bind their path-based transports to one file each.
 *
 * One transport per opened book: whoever opens the book owns it. [CoreComicSource.close]
 * closes its transport (outside any lock), so backends release their session there; the
 * default no-op covers pure in-memory fakes.
 */
interface RangeTransport : java.io.Closeable {
    /** Remote file size in bytes. Needed up front: ZIP parsing starts from the tail. */
    @Throws(IOException::class)
    fun sizeBytes(): Long

    /** Exactly [length] bytes from [offset], or IOException on short read. Never partial. */
    @Throws(IOException::class)
    fun readAt(offset: Long, length: Int): ByteArray

    /** Releases the session behind this transport. Defaults to nothing to release. */
    override fun close() {
    }
}
