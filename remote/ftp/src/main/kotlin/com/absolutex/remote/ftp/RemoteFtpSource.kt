package com.absolutex.remote.ftp

import com.absolutex.model.Page
import java.io.Closeable
import java.io.InputStream

/**
 * A remote book whose pages already exist as encoded images on an FTP/FTPS server.
 *
 * This is deliberately NOT `com.absolutex.source.ComicSource`. The shape matches today, but the
 * lifecycle differs: a remote source owns a location, a credential lookup and a control
 * connection with a REST/RETR round trip behind every range — none of which `ComicSource`
 * models. Folding remotes in means editing that lead-owned file, so this lane keeps its own
 * contract, the same pattern `:source:pdf` used for `RenderedPageSource`.
 *
 * TODO(source-api): unify with `ComicSource` once both lanes merge. Needs, in lead-owned files
 * this lane must not touch:
 *   1. A shared supertype in `source/api/ComicSource.kt` (e.g. `PageContainer : Closeable`)
 *      that both `ComicSource` and this interface extend, so `:feature:reader` holds either.
 *   2. A shared cover-size policy — this lane bounds covers at
 *      `FtpZipSource.COVER_MAX_BYTES`; local sources bound nothing.
 *   3. An error taxonomy: local reads fail on corrupt bytes, remote reads also fail on dropped
 *      control connections (reconnect is per-source today, inside `CommonsNetFtpTransport`).
 */
interface RemoteFtpSource : Closeable {
    /** Pages in read order. */
    val pages: List<Page>

    /** Random-access read of one page. Callers own the stream; each call costs >= 1 RETR. */
    fun openPage(index: Int): InputStream

    /** Cover without pulling the archive: index transfers plus the first entry only. */
    fun openCover(): InputStream = openPage(0)
}
