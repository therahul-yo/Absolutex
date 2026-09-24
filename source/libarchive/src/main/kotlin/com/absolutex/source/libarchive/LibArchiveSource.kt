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
    val isEncrypted: Boolean,
    private val passphrase: ArchivePassphrase,
) : ComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        // By ordinal, never by name: two entries can share a name, and names do not survive a
        // JNI round trip byte-for-byte (see nativeList in archive_jni.c).
        val bytes = traced("absx.entryExtract") {
            passphrase.useBytes { password ->
                openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinals[index], password) }
            }
        } ?: throw IOException("unreadable entry: ${page.entryName}")
        return ByteArrayInputStream(bytes)
    }

    /** Owns no descriptor; clear the session password and reject subsequent reads. */
    override fun close() = passphrase.close()

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
        fun open(openFd: () -> ParcelFileDescriptor): LibArchiveSource = open(null, openFd)

        /**
         * Copies [passphrase]; the caller owns and should clear its CharArray after this call.
         * Only a successful encrypted source retains its copy, until [close]. UTF-8 passwords
         * must be nonempty and contain no NUL (libarchive's C-string contract).
         * TODO(lead): catch ArchivePasswordException at the reader open boundary, prompt/retry
         * for required/rejected passwords, and show unsupported encryption without retrying.
         */
        fun open(passphrase: CharArray?, openFd: () -> ParcelFileDescriptor): LibArchiveSource =
            traced("absx.archiveOpen") {
                val owned = ArchivePassphrase(passphrase)
                var transferred = false
                try {
                    owned.useBytes { password -> openWithPassword(openFd, owned, password) }
                        .also { transferred = true }
                } finally {
                    if (!transferred) owned.close()
                }
            }

        /**
         * The container's ComicInfo.xml, or null. **Never throws.**
         *
         * That is the guarantee #38 exists to deliver, and it is load-bearing: this runs
         * unconditionally on every open, so a corrupt or truncated sidecar — or a wrong password
         * against an encrypted one — must cost the metadata and nothing else. The branch this
         * was rebuilt from wrapped the failure in another IOException and rethrew it, which
         * turns a bad sidecar into a book that will not open at all.
         *
         * The cap is checked after extraction because the limit would otherwise have to live in
         * JNI, and it is [MAX_COMIC_INFO_BYTES] rather than a literal so it is stated once.
         */
        private fun comicInfoFrom(
            openFd: () -> ParcelFileDescriptor,
            raw: Array<ByteArray>,
            password: ByteArray?,
        ): ComicInfo? = runCatching {
            ComicInfoLoader.from(raw.map { String(it, Charsets.UTF_8) }) { ordinal ->
                val bytes = openFd().use { pfd -> LibArchive.nativeExtract(pfd.fd, ordinal, password) }
                bytes?.takeIf { it.size <= MAX_COMIC_INFO_BYTES }?.let { ordinal to it }
            }
        }.getOrNull()

        private fun openWithPassword(
            openFd: () -> ParcelFileDescriptor,
            owned: ArchivePassphrase,
            password: ByteArray?,
        ): LibArchiveSource {
            // Out-params rather than a richer return type: the listing already crosses JNI, and
            // whether it reached clean EOF — and whether anything in it is encrypted — are the
            // two bits separating "this is the whole book" from "this is what survived".
            val complete = BooleanArray(1)
            val encrypted = BooleanArray(1)
            val raw = traced("absx.entryList") {
                openFd().use { LibArchive.nativeList(it.fd, complete, encrypted, password) }
            }
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
            val info = comicInfoFrom(openFd, raw, password)
            // Only recovery pays for payload validation. A single password probe verifies the
            // session passphrase before any page is read; readability is counted in a single
            // pass over the discovered ordinals.
            val recovery = !complete[0] || (info?.pageCount ?: 0) > pages.size
            val readability = computeReadability(openFd, ordinals, info, recovery, encrypted[0], password)
            // A plain archive keeps no copy of the password it never needed.
            if (!encrypted[0]) owned.forget()
            return LibArchiveSource(openFd, pages, ordinals, info, readability, encrypted[0], owned)
        }

        /**
         * The recovery report, or null — and null on every ordinary open, encrypted or not.
         *
         * [ComicSource.pageReadability] is recovery-only by contract ("null means unverified, NOT
         * that every page is readable"), and [PageReadability.inspect] decompresses every page,
         * which is why it is "only used on recovery". Encryption is not recovery. Routing every
         * encrypted open through the count reported a damage notice on healthy books and read the
         * whole archive before page one; a torn page in an encrypted archive now behaves exactly
         * as one in a plain archive already did — unreported until reached, then degraded.
         *
         * The password probe still runs for every encrypted open, recovery or not: a wrong
         * passphrase must fail closed here rather than on some later page. One descriptor serves
         * both the probe and the count, so a recovery open is still a single archive scan.
         */
        private fun computeReadability(
            openFd: () -> ParcelFileDescriptor,
            ordinals: IntArray,
            info: ComicInfo?,
            recovery: Boolean,
            encrypted: Boolean,
            password: ByteArray?,
        ): PageReadability? {
            if (!recovery && !encrypted) return null
            return openFd().use { probeFd ->
                if (encrypted) probePassword(probeFd, ordinals, password)
                if (!recovery) return@use null
                PageReadability.inspect(ordinals.toList(), info?.pageCount) { ordinal ->
                    LibArchive.nativeExtract(probeFd.fd, ordinal, password)?.isNotEmpty() == true
                }
            }
        }

        /**
         * Fails closed on a wrong or missing passphrase before any page is read, rather than
         * mid-session on some later page. Called for that effect alone: the native side throws
         * WrongPasswordException / PasswordRequiredException for a password diagnostic, so a
         * *null* return means the entry could not be read with a password that is fine — a torn
         * CRC or a truncated entry. That must not refuse the book (§2 degrades), so it is ignored
         * here and the page fails when it is reached. The passphrase is passed through uncopied:
         * copy_passphrase in the JNI takes its own copy and wipes it on every exit, so a second
         * Kotlin-side copy would only be one more plaintext array to forget to clear.
         */
        private fun probePassword(probeFd: ParcelFileDescriptor, ordinals: IntArray, password: ByteArray?) {
            val firstOrdinal = ordinals.getOrNull(0) ?: return
            LibArchive.nativeExtract(probeFd.fd, firstOrdinal, password)
        }
    }
}
