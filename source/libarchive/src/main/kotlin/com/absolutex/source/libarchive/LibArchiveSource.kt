package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Covers .cbz/.cbr/.cb7/.cbt through one libarchive reader.
 *
 * Takes a FACTORY, not a descriptor, and opens a fresh one per read. That is a correctness
 * requirement, not a style choice:
 *
 * A descriptor cannot simply be dup()'d and handed to another thread, because the copy shares
 * its file offset and concurrent readers then corrupt each other. The native side normally
 * side-steps that by re-opening /proc/self/fd/N, which yields an independent file description
 * — but that re-check fails for a SAF descriptor, since SAF exists precisely to grant access
 * the app does not have by path. Under the reader's parallel prefetch that produced real
 * corruption on device ("Prefix found", "Bad RAR file data") rather than a clean error.
 *
 * Opening per read costs about a millisecond against a 250 ms budget and is unconditionally
 * safe, so the decode pool keeps fanning pages across the big cores (§3).
 */
class LibArchiveSource private constructor(
    private val openFd: () -> ParcelFileDescriptor,
    override val pages: List<Page>,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        val bytes = openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, page.entryName) }
            ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    /** Owns no descriptor — each read opens and closes its own. */
    override fun close() = Unit

    companion object {
        /**
         * @param openFd must return a NEW, independent descriptor on every call.
         * @throws IOException if the container cannot be read at all. A container that reads
         * partially yields the pages that are readable — §2 requires degrading, never crashing.
         */
        fun open(openFd: () -> ParcelFileDescriptor): LibArchiveSource {
            val names = openFd().use { LibArchive.nativeList(it.fd) }
                ?: throw IOException("not a readable archive")
            val pages = names
                .filter { EntryFilter.isPage(it) }
                .sortedWith(NaturalOrder)
                .mapIndexed { i, name -> Page(index = i, entryName = name) }
            return LibArchiveSource(openFd, pages)
        }
    }
}
