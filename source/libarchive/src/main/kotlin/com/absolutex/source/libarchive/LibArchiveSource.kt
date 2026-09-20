package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
import android.os.Trace
import com.absolutex.model.ComicInfo
import com.absolutex.model.Page
import com.absolutex.source.ComicInfoLoader
import com.absolutex.source.ComicSource
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import com.absolutex.source.PageReadability
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
    override val comicInfo: ComicInfo?,
    override val pageReadability: PageReadability?,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        // By ordinal, never by name: two entries can share a name, and names do not survive a
        // JNI round trip byte-for-byte (see nativeList in archive_jni.c).
        val bytes = traced("absx.entryExtract") {
            openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinals[index]) }
        }
            ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    /** Owns no descriptor — each read opens and closes its own. */
    override fun close() = Unit

    companion object {
        private const val MAX_COMIC_INFO_BYTES = 1024 * 1024

        private inline fun <T> traced(name: String, block: () -> T): T {
            Trace.beginSection(name)
            return try {
                block()
            } finally {
                Trace.endSection()
            }
        }

        /**
         * @param openFd must return a NEW, independent descriptor on every call.
         * @throws IOException if the container cannot be read at all. A container that reads
         * partially retains discovered slots and reports readable payloads separately.
         */
        fun open(openFd: () -> ParcelFileDescriptor): LibArchiveSource = traced("absx.archiveOpen") {
            // Out-param rather than a richer return type: the listing already crosses JNI, and
            // whether it reached clean EOF is the one bit separating "this is the whole book"
            // from "this is what survived".
            val complete = BooleanArray(1)
            val raw = traced("absx.entryList") { openFd().use { LibArchive.nativeList(it.fd, complete) } }
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
            // Locate against the RAW entry list (ordinals must match nativeExtract), parse once;
            // a missing, unreadable or malformed ComicInfo costs the metadata, not the open.
            // Any failure below — unreadable entry, malformed XML, a sidecar too large to be
            // real — costs the metadata, not the book. The cap is checked after extraction
            // because the size limit would otherwise have to live in JNI; 1 MiB is far past
            // any ComicInfo.xml a real scan carries.
            //
            // runCatching, never a throwing read. This is what #38 exists to deliver: a corrupt
            // or truncated ComicInfo.xml degrades to comicInfo = null instead of killing the
            // book open. The branch this was rebuilt from had a version that caught IOException
            // and rethrew it wrapped, called unconditionally, which reverses that guarantee —
            // it is deliberately not carried over. MAX_COMIC_INFO_BYTES rather than a literal,
            // so the limit is stated once and cannot drift.
            val info = runCatching {
                ComicInfoLoader.from(raw.map { String(it, Charsets.UTF_8) }) { ordinal ->
                    val bytes = openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinal) }
                    bytes?.takeIf { it.size <= MAX_COMIC_INFO_BYTES }?.let { ordinal to it }
                }
            }.getOrNull()
            // Only recovery pays for payload validation. Header discovery alone overcounts the
            // final cut-off entry; metadata can also reveal a cut exactly between entries.
            val recovery = !complete[0] || (info?.pageCount ?: 0) > pages.size
            val readability = if (recovery) {
                PageReadability.inspect(ordinals.toList(), info?.pageCount) { ordinal ->
                    openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinal) }?.isNotEmpty() == true
                }
            } else {
                null
            }
            LibArchiveSource(openFd, pages, ordinals, info, readability)
        }
    }
}
