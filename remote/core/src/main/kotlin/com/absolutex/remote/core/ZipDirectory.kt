package com.absolutex.remote.core

import java.io.IOException

/**
 * Minimal ZIP central-directory parser over a random-read function: EOCD tail first, then the
 * whole central directory in one ranged read — the index that makes remote streaming possible
 * without pulling the whole file. Entry bytes stay on the server until a page is opened.
 *
 * Hardened for hostile input (§2 degrade rule: IOException with a message, never a crash):
 * the backwards EOCD scan admits a candidate only with its 22 bytes in bounds, a servable
 * flavour and its comment running to end of file (a signature inside the comment is not the
 * EOCD); every central-directory length is bounded before it is read; ZIP64 and multi-disk
 * archives are rejected outright. Structurally valid entries are all returned — filtering by
 * name, method or directory bit is the source's job, so one hostile record can still hide
 * nothing: the first unparseable entry ends parsing with an error instead of a half-index.
 */
object ZipDirectory {

    /** One central-directory entry: everything needed to locate the entry's bytes later. */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8

    /**
     * No end-of-central-directory anywhere in the tail: not a ZIP, or one truncated past
     * recognition. Sources that plan a whole-file cached fallback catch this specifically;
     * everything else treats it as an IOException like any other hostile input.
     */
    class EocdNotFoundException(message: String) : IOException(message)

    /** Opens the directory: EOCD tail first, then the whole central directory in one read. */
    @Throws(IOException::class)
    fun open(read: (offset: Long, length: Int) -> ByteArray, size: Long): List<Entry> {
        val eocd = locateEocd(read, size)
            ?: throw EocdNotFoundException("no zip end-of-central-directory (or multi-disk/zip64 TODO)")
        return readCentralDirectory(read, eocd, size)
    }

    /** Local file-data offset for one entry, resolved lazily on open — never indexed. */
    @Throws(IOException::class)
    fun localDataOffsetOf(read: (offset: Long, length: Int) -> ByteArray, entry: Entry): Long {
        val header = read(entry.localHeaderOffset, LF_FIXED)
        if (s32(header, 0) != LF_SIG) throw IOException("bad local header: ${entry.name}")
        return entry.localHeaderOffset + LF_FIXED +
            u16(header, LF_NAME_OFF) + u16(header, LF_EXTRA_OFF)
    }

    internal fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl SHIFT_8)

    internal fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + HALF_WORD).toLong() shl SHIFT_16)

    internal fun s32(bytes: ByteArray, offset: Int): Int = u32(bytes, offset).toInt()

    private data class Eocd(val cdOffset: Long, val cdSize: Long)

    private fun locateEocd(read: (Long, Int) -> ByteArray, size: Long): Eocd? {
        val tailLen = minOf(size, EOCD_SCAN_MAX).toInt()
        val tail = read(size - tailLen, tailLen)
        // Backwards: the EOCD is the LAST record; a forward scan can hit a false positive
        // inside a stored entry's data, and a signature inside the comment is not the EOCD.
        var base = tail.size - EOCD_LEN
        while (base >= 0) {
            if (s32(tail, base) == EOCD_SIG) {
                parseEocdAt(tail, base)?.let { return it }
            }
            base--
        }
        return null
    }

    private fun parseEocdAt(tail: ByteArray, base: Int): Eocd? {
        // The fixed 22 bytes must exist: an 8-byte file holding half a signature is simply
        // not an archive, and reading past it is the crash this guards.
        if (base + EOCD_LEN > tail.size) return null
        if (isUnsupportedFlavor(tail, base)) return null
        if (base + EOCD_LEN + u16(tail, base + EOCD_COMMENT_OFF) != tail.size) return null
        return Eocd(u32(tail, base + EOCD_OFFSET_OFF), u32(tail, base + EOCD_SIZE_OFF))
    }

    private fun isUnsupportedFlavor(tail: ByteArray, base: Int): Boolean {
        // Flavors ranged reads cannot serve: multi-disk splits the directory across files,
        // ZIP64 moves the counts (and the 0xFFFF entry count) elsewhere.
        if (u16(tail, base + EOCD_DISK_OFF) != 0) return true
        if (u16(tail, base + EOCD_CD_DISK_OFF) != 0) return true
        if (u16(tail, base + EOCD_DISK_COUNT_OFF) == ZIP64_U16) return true
        if (u16(tail, base + EOCD_COUNT_OFF) == ZIP64_U16) return true
        if (u32(tail, base + EOCD_SIZE_OFF) == ZIP64_U32) return true
        return u32(tail, base + EOCD_OFFSET_OFF) == ZIP64_U32
    }

    private fun readCentralDirectory(
        read: (Long, Int) -> ByteArray,
        eocd: Eocd,
        size: Long,
    ): List<Entry> {
        // A directory bigger than the cap is hostile (order of a million entries); one outside
        // the file is truncated. Either way refuse before allocating.
        if (eocd.cdSize > CD_MAX_BYTES) throw IOException("central directory too large: ${eocd.cdSize}")
        if (eocd.cdOffset < 0 || eocd.cdOffset + eocd.cdSize > size) {
            throw IOException("ZIP central directory outside file")
        }
        val raw = read(eocd.cdOffset, eocd.cdSize.toInt())
        val entries = mutableListOf<Entry>()
        var cursor = 0
        while (cursor + CD_FIXED <= raw.size) {
            cursor = nextEntry(raw, cursor, entries)
        }
        return entries
    }

    private fun nextEntry(raw: ByteArray, cursor: Int, entries: MutableList<Entry>): Int {
        if (s32(raw, cursor) != CD_SIG) throw IOException("truncated central directory")
        val nameLen = u16(raw, cursor + CD_NAME_OFF)
        val extraLen = u16(raw, cursor + CD_EXTRA_OFF)
        val commentLen = u16(raw, cursor + CD_COMMENT_OFF)
        // Lengths are untrusted: a run past the buffer is hostile input, not a long filename.
        if (nameLen + extraLen + commentLen > raw.size - (cursor + CD_FIXED)) {
            throw IOException("central directory entry past end")
        }
        val total = CD_FIXED + nameLen + extraLen + commentLen
        entryAt(raw, cursor, nameLen)?.let { entries.add(it) }
        return cursor + total
    }

    private fun entryAt(raw: ByteArray, cursor: Int, nameLen: Int): Entry? {
        if (nameLen == 0) return null
        // Malformed names degrade to mojibake, never to a crash.
        val name = String(raw.copyOfRange(cursor + CD_FIXED, cursor + CD_FIXED + nameLen), Charsets.UTF_8)
        if (name.endsWith("/")) return null
        return Entry(
            name = name,
            method = u16(raw, cursor + CD_METHOD_OFF),
            compressedSize = u32(raw, cursor + CD_COMP_OFF),
            uncompressedSize = u32(raw, cursor + CD_UNCOMP_OFF),
            localHeaderOffset = u32(raw, cursor + CD_LHO_OFF),
        )
    }

    private const val EOCD_SIG = 0x06054B50
    private const val CD_SIG = 0x02014B50
    private const val LF_SIG = 0x04034B50
    private const val EOCD_LEN = 22
    private const val EOCD_DISK_OFF = 4
    private const val EOCD_CD_DISK_OFF = 6
    private const val EOCD_DISK_COUNT_OFF = 8
    private const val EOCD_COUNT_OFF = 10
    private const val EOCD_SIZE_OFF = 12
    private const val EOCD_OFFSET_OFF = 16
    private const val EOCD_COMMENT_OFF = 20
    private const val EOCD_SCAN_MAX = 65_535L + EOCD_LEN
    private const val CD_FIXED = 46
    private const val CD_METHOD_OFF = 10
    private const val CD_COMP_OFF = 20
    private const val CD_UNCOMP_OFF = 24
    private const val CD_NAME_OFF = 28
    private const val CD_EXTRA_OFF = 30
    private const val CD_COMMENT_OFF = 32
    private const val CD_LHO_OFF = 42
    private const val LF_FIXED = 30
    private const val LF_NAME_OFF = 26
    private const val LF_EXTRA_OFF = 28

    /**
     * A directory bigger than this is hostile (order of a million entries): a 200-page CBZ
     * indexes in ~13 KiB. 32 MiB covers legitimate tens-of-thousands-of-entries archives on
     * either transport, and oversized spans bypass the block cache and stream instead of
     * self-evicting it (see SeekableReader) — so the cap is a hostility bound, not a
     * cache-size bound.
     */
    internal const val CD_MAX_BYTES = 32L * 1024 * 1024
    private const val ZIP64_U16 = 0xFFFF
    private const val ZIP64_U32 = 0xFFFFFFFFL
    private const val BYTE_MASK = 0xFF
    private const val SHIFT_8 = 8
    private const val SHIFT_16 = 16
    private const val HALF_WORD = 2
}
