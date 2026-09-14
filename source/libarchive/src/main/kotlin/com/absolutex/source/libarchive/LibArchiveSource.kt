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
    /** Archive ordinal of each page, parallel to [pages]. Sorting reorders pages, not ordinals. */
    private val ordinals: IntArray,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        // By ordinal, never by name: two entries can share a name, and names do not survive a
        // JNI round trip byte-for-byte (see nativeList in archive_jni.c).
        val bytes = openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinals[index]) }
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
            val raw = openFd().use { LibArchive.nativeList(it.fd) }
                ?: throw IOException("not a readable archive")
            // String(bytes, UTF_8) substitutes U+FFFD for malformed input instead of throwing, so
            // a Shift-JIS name from an old Japanese scan degrades to mojibake, not to a crash.
            // TODO(phase6): charset detection for legacy non-UTF-8 names.
            val kept = raw
                .mapIndexed { ordinal, bytes -> ordinal to String(bytes, Charsets.UTF_8) }
                .filter { (_, name) -> EntryFilter.isPage(name) }
                // Stable sort: entries with identical names keep their archive order.
                .sortedWith(compareBy(NaturalOrder) { it.second })
            val pages = kept.mapIndexed { i, (_, name) -> Page(index = i, entryName = name) }
            val ordinals = IntArray(kept.size) { kept[it].first }
            return LibArchiveSource(openFd, pages, ordinals)
        }
    }
}
