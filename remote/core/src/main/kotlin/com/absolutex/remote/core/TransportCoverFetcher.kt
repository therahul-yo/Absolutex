package com.absolutex.remote.core

import java.io.IOException

/**
 * Cover bytes behind a server id plus a path — the browse grid's entry point, next to
 * [TransportBookOpener], taking the same `(serverId, path) -> RangeTransport` seam so backends
 * wire one function and both flows share it. Callers pass the pieces the listing already holds;
 * nothing here mints or parses an `absolutex-remote://` Uri, so covers never need a hand-built
 * one ([encodeRemoteUri] stays the only minting point, for recents/widgets that store Uris).
 *
 * One transport per call — transports are cheap (SMB/FTP connect lazily) and never shared
 * across books. Whoever opens the book owns the transport: [CoreComicSource.open] already
 * closes it on every non-Ready exit, and the Ready source is closed below, so no exit leaks a
 * session behind a grid cell. Transport failures (unreachable, auth) propagate as IOException
 * for the grid's generic error path; only a non-ZIP container returns a verdict instead of
 * bytes, as an IOException naming the Phase F download fallback.
 *
 * TODO(agent3): call [coverBytes] from the browse grid's cover pass with the (serverId, path)
 * the listing already holds — never open a full book, and never hand-build a Uri, for a cover.
 * TODO(lead): the screens own the ThumbRequest wiring; mint the
 * sourceId with [encodeRemoteUri] so a re-downloaded local copy shares the cached cover.
 */
class TransportCoverFetcher(
    private val transports: suspend (serverId: String, path: String) -> RangeTransport,
) {

    /**
     * First-page bytes of the book at [path] on [serverId], bounded by [maxBytes].
     * Suspending: resolving the server id reads the server list (DataStore), so callers must
     * not call this on the main thread — the grid calls it from its cover coroutine, same as
     * every other remote path.
     */
    @Throws(IOException::class)
    suspend fun coverBytes(
        serverId: String,
        path: String,
        maxBytes: Long = DEFAULT_COVER_MAX_BYTES,
    ): ByteArray {
        val transport = transports(serverId, path)
        val opened = CoreComicSource.open(transport, path.substringAfterLast('/'))
        // open() owns the transport on every non-Ready exit already; Ready hands it to the
        // source, which the use-block below closes — either way no exit leaks a session.
        val source = when (opened) {
            is RemoteOpenResult.DownloadRequired ->
                throw IOException("cover needs a full download: ${opened.reason}")
            is RemoteOpenResult.Ready -> opened.source as CoreComicSource
        }
        source.use { return cappedCover(it, maxBytes) }
    }

    /**
     * First-page bytes through an open book: the stream is already ranged and readNBytes caps
     * the transfer, so the cap guards a lying central directory, not the network.
     */
    private fun cappedCover(source: CoreComicSource, maxBytes: Long): ByteArray {
        // Empty archive: openCover would throw IndexOutOfBounds, which is a bug report,
        // not a book. Fail with a message like every other unreadable book.
        if (source.pages.isEmpty()) throw IOException("archive has no pages")
        return source.openCover().use { cover -> cover.readNBytes(maxBytes.plus(1).toInt()) }
            .also { bytes -> if (bytes.size.toLong() > maxBytes) throw IOException("cover too large") }
    }

    companion object {
        // A cover is display-sized; larger means a hostile entry, not art. Same budget as the
        // SMB/FTP cover helpers so every backend refuses the same hostile first page.
        const val DEFAULT_COVER_MAX_BYTES = 12L * 1024 * 1024
    }
}
