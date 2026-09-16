package com.absolutex.remote.smb

import java.io.IOException

/** Cover bytes for the library grid: first page only, bounded, no full download. */
object RemoteThumbnails {

    // A cover is display-sized; larger means a hostile entry, not art (cf. MAX_REMOTE_ENTRY_BYTES).
    const val DEFAULT_COVER_MAX_BYTES = 12L * 1024 * 1024

    @Throws(IOException::class)
    fun coverBytes(
        source: RemoteComicSource,
        maxBytes: Long = DEFAULT_COVER_MAX_BYTES,
    ): ByteArray {
        // Empty archive: openCover would throw IndexOutOfBounds, which is a bug report,
        // not a book. Fail with a message like every other unreadable book.
        if (source.pages.isEmpty()) throw IOException("archive has no pages")
        // The stream is already ranged and readNBytes caps the transfer: the cap guards a
        // lying central directory, not the network.
        return source.openCover().use { it.readNBytes(maxBytes.plus(1).toInt()) }
            .also { if (it.size.toLong() > maxBytes) throw IOException("cover too large") }
    }
}
