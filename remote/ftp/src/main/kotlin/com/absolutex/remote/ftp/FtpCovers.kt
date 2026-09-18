package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Cover bytes for the library grid over FTP: first page only, bounded, no full download.
 *
 * Mirrors `com.absolutex.remote.smb.RemoteThumbnails` on the FTP source shape: [FtpZipSource]
 * already refuses an empty archive and a giant first entry in [RemoteFtpSource.openCover], and
 * this adds the transfer cap — a lying central directory can declare a small entry while the
 * wire serves more, so the read itself is bounded, not just the declaration.
 *
 * TODO(agent3): the browse grid's cover pass calls [coverBytes] with the (serverId, path) the
 * listing already holds, through the FTP backend's transport — covers never need a hand-built
 * Uri, and the grid never opens a full book.
 * TODO(lead): the screens own the ThumbRequest wiring; the
 * sourceId story is `encodeRemoteUri(serverId, path)` so a re-downloaded local copy shares the
 * cached cover instead of minting a second entry.
 */
object FtpCovers {

    // A cover is display-sized; larger means a hostile entry, not art
    // (cf. FtpZipSource.COVER_MAX_BYTES, which bounds the declaration — this bounds the wire).
    const val DEFAULT_COVER_MAX_BYTES = 12L * 1024 * 1024

    @Throws(IOException::class)
    fun coverBytes(
        source: RemoteFtpSource,
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
