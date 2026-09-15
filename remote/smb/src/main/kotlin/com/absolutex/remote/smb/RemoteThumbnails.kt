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
        val size = source.pages.getOrNull(0)?.sizeBytes ?: -1
        if (size > maxBytes) throw IOException("cover too large: $size bytes")
        // The stream is already ranged; cap guards a lying central directory, not the network.
        return source.openCover().use { it.readNBytes(maxBytes.plus(1).toInt()) }
            .also { if (it.size.toLong() > maxBytes) throw IOException("cover too large") }
    }
}
