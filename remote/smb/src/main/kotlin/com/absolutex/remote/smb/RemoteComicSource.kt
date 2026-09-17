package com.absolutex.remote.smb

import com.absolutex.model.Page
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * A remote book's pages, mirroring `com.absolutex.source.ComicSource` without depending on it.
 *
 * Deliberately NOT `ComicSource`: that interface is owned by the lead lane with the reader
 * contract, and unifying needs shared decisions this lane must not make alone.
 *
 * TODO(source-api): unify with ComicSource once the lead approves. Needs from files this
 * lane does not own:
 *   1. A shared supertype in `source/api/ComicSource.kt` (e.g. `PageContainer : Closeable`)
 *      so :feature:reader can hold a local or remote book behind one type.
 *   2. A handoff for the fd-backed libarchive bridge (`source/libarchive`): either a local
 *      index-cache file the bridge can open by fd, or a JNI read-callback for ranged remote
 *      bytes. Until then this lane serves ZIP/CBZ by parsing the central directory itself
 *      and keeps its own types with :core:model's Page (entryName/sizeBytes match).
 */
interface RemoteComicSource : Closeable {
    val pages: List<Page>

    /** Random-access read of one page. Callers own the stream. Ranged fetch, no full download. */
    fun openPage(index: Int): InputStream

    /** Cover without downloading the archive: central directory plus the first entry only. */
    fun openCover(): InputStream {
        // Empty archive: openPage(0) would throw IndexOutOfBounds, which is a bug report,
        // not a book. Fail with a message like every other unreadable book.
        if (pages.isEmpty()) throw IOException("archive has no pages")
        return openPage(0)
    }
}
