package com.absolutex.remote.ftp

import java.io.IOException

/**
 * Minimal ZIP central-directory parser over a random-read function.
 *
 * Reads only the EOCD tail plus the central directory in one RETR each — the index that makes
 * FTP streaming possible without pulling the whole file. Entry bytes stay on the server until a
 * page is opened. Sizes come from the central directory (offsets +20/+24); the local header
 * (offsets +18/+22) is consulted only for the data start — never mix the two layouts.
 */
internal object FtpZipDirectory {

    /** One central-directory entry: everything needed to locate the entry's bytes later. */
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    /** Opens the directory: EOCD tail first, then the whole central directory in one RETR. */
    @Throws(IOException::class)
    fun open(read: (Long, Int) -> ByteArray, size: Long): List<Entry> {
        if (size < EOCD_LEN) throw IOException("too small for a ZIP end record: $size bytes")
        val eocd = locateEocd(read, size)
        return readCentralDirectory(read, eocd, size)
    }

    internal fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl SHIFT_8)

    internal fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + HALF_WORD).toLong() shl SHIFT_16)

    internal fun s32(bytes: ByteArray, offset: Int): Int = u32(bytes, offset).toInt()

    private data class Eocd(val cdOffset: Long, val cdSize: Long)

    private fun locateEocd(read: (Long, Int) -> ByteArray, size: Long): Eocd {
        val tailLen = minOf(size, EOCD_SCAN_MAX).toInt()
        val tail = read(size - tailLen, tailLen)
        // The EOCD sits at the very end modulo a ≤64 KiB comment, so scan backwards for its sig.
        var cursor = tail.size - EOCD_LEN
        while (cursor >= MIN_CURSOR) {
            if (s32(tail, cursor) == EOCD_SIG) return validatedEocd(tail, cursor)
            cursor--
        }
        // Fail fast: without an index the only fallback is pulling the whole file for a local
        // scan (TODO(remote-ftp): whole-file cached fallback for non-ZIP inputs).
        throw IOException("not a ZIP archive: end record not found (TODO(remote-ftp): cached fallback)")
    }

    private fun validatedEocd(tail: ByteArray, cursor: Int): Eocd {
        val entries = u16(tail, cursor + EOCD_ENTRIES_OFF)
        val offset = u32(tail, cursor + EOCD_OFFSET_OFF)
        if (entries == ZIP64_U16 || offset == ZIP64_U32) throw IOException("zip64 archives are not supported")
        return Eocd(offset, u32(tail, cursor + EOCD_SIZE_OFF))
    }

    private fun readCentralDirectory(read: (Long, Int) -> ByteArray, eocd: Eocd, size: Long): List<Entry> {
        // A directory bigger than the cap is hostile (millions of entries); one outside the file
        // is truncated. Either way refuse before allocating.
        if (eocd.cdSize > CD_MAX_BYTES) throw IOException("ZIP central directory too large: ${eocd.cdSize}")
        if (eocd.cdOffset < MIN_CURSOR || eocd.cdOffset + eocd.cdSize > size) {
            throw IOException("ZIP central directory outside file")
        }
        val raw = read(eocd.cdOffset, eocd.cdSize.toInt())
        val entries = mutableListOf<Entry>()
        var cursor = MIN_CURSOR
        while (cursor >= MIN_CURSOR && cursor + CD_FIXED <= raw.size) {
            cursor = nextEntry(raw, cursor, entries)
        }
        return entries
    }

    private fun nextEntry(raw: ByteArray, cursor: Int, entries: MutableList<Entry>): Int {
        val nameLen = u16(raw, cursor + CD_NAME_OFF)
        val total = CD_FIXED + nameLen + u16(raw, cursor + CD_EXTRA_OFF) + u16(raw, cursor + CD_COMMENT_OFF)
        val aligned = s32(raw, cursor) == CD_SIG
        val fits = cursor + total <= raw.size
        // A foreign signature or an overrunning entry ends the walk; a bad name only skips its
        // own entry, so one hostile record cannot hide the readable rest.
        if (aligned && fits) {
            entryAt(raw, cursor, nameLen)?.let { entries.add(it) }
            return cursor + total
        }
        return DONE
    }

    private fun entryAt(raw: ByteArray, cursor: Int, nameLen: Int): Entry? {
        if (nameLen == EMPTY_NAME) return null
        val nameBytes = raw.copyOfRange(cursor + CD_FIXED, cursor + CD_FIXED + nameLen)
        // Malformed names degrade to mojibake, never to a crash — same rule as LibArchiveSource.
        val name = String(nameBytes, Charsets.UTF_8)
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
    private const val EOCD_LEN = 22
    private const val EOCD_ENTRIES_OFF = 10
    private const val EOCD_SIZE_OFF = 12
    private const val EOCD_OFFSET_OFF = 16
    private const val MAX_COMMENT = 65_535L
    private const val EOCD_SCAN_MAX = MAX_COMMENT + EOCD_LEN
    private const val CD_FIXED = 46
    private const val CD_METHOD_OFF = 10
    private const val CD_COMP_OFF = 20
    private const val CD_UNCOMP_OFF = 24
    private const val CD_NAME_OFF = 28
    private const val CD_EXTRA_OFF = 30
    private const val CD_COMMENT_OFF = 32
    private const val CD_LHO_OFF = 42

    /**
     * A central directory above this is hostile (order of a million entries); refuse first. Also
     * bounded by the block cache: a directory bigger than [FtpBlockCache.MAX_BYTES] can never be
     * assembled back out of it (its first blocks get evicted before the fetch finishes), so this
     * must never exceed that cap.
     */
    private const val CD_MAX_BYTES = FtpBlockCache.MAX_BYTES
    private const val ZIP64_U16 = 0xFFFF
    private const val ZIP64_U32 = 0xFFFFFFFFL
    private const val MIN_CURSOR = 0
    private const val DONE = -1
    private const val EMPTY_NAME = 0
    private const val BYTE_MASK = 0xFF
    private const val SHIFT_8 = 8
    private const val SHIFT_16 = 16
    private const val HALF_WORD = 2
}
