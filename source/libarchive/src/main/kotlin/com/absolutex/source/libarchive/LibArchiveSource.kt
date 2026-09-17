package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor
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
    val isEncrypted: Boolean,
    private val passphrase: ArchivePassphrase,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        // By ordinal, never by name: two entries can share a name, and names do not survive a
        // JNI round trip byte-for-byte (see nativeList in archive_jni.c).
        val bytes = passphrase.useBytes { password ->
            openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinals[index], password) }
        } ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    /** Owns no descriptor; clear the session password and reject subsequent reads. */
    override fun close() = passphrase.close()

    companion object {
        /**
         * @param openFd must return a NEW, independent descriptor on every call.
         * @throws IOException if the container cannot be read at all. A container that reads
         * partially retains discovered slots and reports readable payloads separately.
         */
        fun open(openFd: () -> ParcelFileDescriptor): LibArchiveSource = open(null, openFd)

        /**
         * Copies [passphrase]; the caller owns and should clear its CharArray after this call.
         * Only a successful encrypted source retains its copy, until [close]. UTF-8 passwords
         * must be nonempty and contain no NUL (libarchive's C-string contract).
         * TODO(lead): catch ArchivePasswordException at the reader open boundary, prompt/retry
         * for required/rejected passwords, and show unsupported encryption without retrying.
         */
        fun open(passphrase: CharArray?, openFd: () -> ParcelFileDescriptor): LibArchiveSource {
            val owned = ArchivePassphrase(passphrase)
            var transferred = false
            return try {
                owned.useBytes { password -> openWithPassword(openFd, owned, password) }
                    .also { transferred = true }
            } finally {
                if (!transferred) owned.close()
            }
        }

        private fun openWithPassword(
            openFd: () -> ParcelFileDescriptor,
            owned: ArchivePassphrase,
            password: ByteArray?,
        ): LibArchiveSource {
            val complete = BooleanArray(1)
            val encrypted = BooleanArray(1)
            val raw = openFd().use { LibArchive.nativeList(it.fd, complete, encrypted, password) }
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
            val info = try {
                ComicInfoLoader.from(raw.map { String(it, Charsets.UTF_8) }) { ordinal ->
                    val bytes = openFd().use { pfd ->
                        LibArchive.nativeExtract(pfd.fd, ordinal, password)
                    }?.let { if (it.size > 1024 * 1024) null else it }
                    bytes?.let { ordinal to it }
                }
            } catch (e: Exception) {
                null
            }
            // Only recovery pays for payload validation. A single password probe verifies
            // the session passphrase before any page is read; readability is counted in
            // a single pass over the discovered ordinals.
            val recovery = !complete[0] || (info?.pageCount ?: 0) > pages.size
            val readability = if (recovery || encrypted[0]) {
                // Single descriptor reused for both probe and readability to avoid many
                // independent archive scans before the first page.
                openFd().use { probeFd ->
                    val probePassword = password?.copyOf() ?: password
                    // Single password probe: read the first discovered page. A wrong/rejected
                    // password fails closed here, never mid-session.
                    if (encrypted[0]) {
                        val firstOrdinal = ordinals.getOrNull(0) ?: -1
                        if (firstOrdinal >= 0) {
                            val probeBytes = LibArchive.nativeExtract(probeFd.fd, firstOrdinal, probePassword)
                            if (probeBytes == null) {
                                throw IOException("Archive password rejected or entry unreadable")
                            }
                        }
                    }
                    // Single-pass readability count: one descriptor scans all page ordinals.
                    PageReadability.inspect(ordinals.toList(), info?.pageCount) { ordinal ->
                        LibArchive.nativeExtract(probeFd.fd, ordinal, password)?.isNotEmpty() == true
                    }
                }
            } else {
                null
            }
            if (!encrypted[0]) owned.forget()
            return LibArchiveSource(openFd, pages, ordinals, info, readability, encrypted[0], owned)
        }
    }
}
