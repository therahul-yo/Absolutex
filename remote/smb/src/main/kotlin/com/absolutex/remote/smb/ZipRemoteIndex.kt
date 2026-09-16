package com.absolutex.remote.smb

import java.io.IOException

/** One ZIP entry's byte ranges. All offsets are absolute file offsets. */
data class ZipEntryRange(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    /** Central-directory offset of this entry's local header; resolved on open, never indexed. */
    val localHeaderOffset: Long,
)

/**
 * ZIP central-directory index built from ranged reads only: tail scan for EOCD, one read for
 * the directory, and nothing else — local header offsets resolve per opened page, so indexing
 * a 200-page archive costs two round trips, not six hundred. A 200 MB archive indexes in
 * kilobytes of traffic. ZIP64 and multi-disk archives are rejected with a message, never
 * misparsed — TODO(remote): ZIP64 EOCD locator for >4 GiB books.
 */
class ZipRemoteIndex private constructor(val entries: List<ZipEntryRange>) {

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        private const val EOCD_SIG = 0x06054b50
        private const val CENTRAL_SIG = 0x02014b50
        private const val LOCAL_SIG = 0x04034b50
        private const val EOCD_MIN_SIZE = 22
        private const val EOCD_TAIL_SIZE = 65_535 + 22
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30
        private const val ZIP64_SENTINEL = 0xFFFFFFFFL
        private const val ZIP64_COUNT_SENTINEL = 0xFFFF
        // Directory is metadata: a 200-page CBZ indexes in ~13 KiB, so anything past a few
        // megabytes is hostile input, not a comic (§2 degrade rule) — and past the block
        // cache it could not be read back anyway (see SeekableSmbReader).
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 8L * 1024 * 1024

        @Throws(IOException::class)
        fun open(reader: SeekableSmbReader): ZipRemoteIndex {
            val size = reader.sizeBytes
            val tailSize = minOf(size, EOCD_TAIL_SIZE.toLong()).toInt()
            // A file shorter than EOCD_MIN_SIZE simply has no EOCD: same throw, no special case.
            val tail = reader.readAt(size - tailSize, tailSize)
            val eocd = parseEocd(tail)
                ?: throw IOException("no zip end-of-central-directory (or multi-disk/zip64 TODO)")
            checkDirectory(eocd, size)
            val dir = reader.readAt(eocd.cdOffset, eocd.cdSize.toInt())
            return ZipRemoteIndex(parseDirectory(dir, eocd.cdCount))
        }

        private fun checkDirectory(eocd: Eocd, size: Long) {
            if (eocd.cdSize > MAX_CENTRAL_DIRECTORY_BYTES) {
                throw IOException("central directory too large: ${eocd.cdSize} bytes")
            }
            if (eocd.cdOffset + eocd.cdSize > size) {
                throw IOException("central directory past end")
            }
        }

        /** Local file-data offset for one entry, resolved lazily on open — never indexed. */
        @Throws(IOException::class)
        fun dataOffsetOf(reader: SeekableSmbReader, entry: ZipEntryRange): Long =
            localDataOffset(reader, entry.localHeaderOffset)

        private data class Eocd(val cdCount: Int, val cdSize: Long, val cdOffset: Long)

        private fun parseEocd(tail: ByteArray): Eocd? {
            // Backwards: the EOCD is the LAST record; a forward scan can hit a false positive
            // inside a stored entry's data. Each candidate must parse whole: bounds, flavour
            // and the comment running to the end of the file, or a signature sitting inside
            // the comment would be taken for the EOCD.
            var base = tail.size - SIG_SIZE
            while (base >= 0) {
                if (sig(tail, base) == EOCD_SIG) {
                    parseEocdAt(tail, base)?.let { return it }
                }
                base--
            }
            return null
        }

        private fun parseEocdAt(tail: ByteArray, base: Int): Eocd? {
            // The fixed 22 bytes must exist: an 8-byte file holding half a signature is
            // simply not an archive, and reading past it is the crash this guards.
            if (base + EOCD_MIN_SIZE > tail.size) return null
            if (isUnsupportedFlavor(tail, base)) return null
            val commentLen = u16(tail, base + EOCD_COMMENT_LEN)
            if (base + EOCD_MIN_SIZE + commentLen != tail.size) return null
            return Eocd(
                u16(tail, base + EOCD_CD_COUNT),
                u32(tail, base + EOCD_CD_SIZE),
                u32(tail, base + EOCD_CD_OFFSET),
            )
        }

        private fun isUnsupportedFlavor(tail: ByteArray, base: Int): Boolean {
            // Flavors ranged reads cannot serve return true, not a half-parsed index:
            // multi-disk splits the directory across files, ZIP64 moves the counts elsewhere.
            if (u16(tail, base + EOCD_DISK_NO) != 0) return true
            if (u16(tail, base + EOCD_CD_DISK) != 0) return true
            if (u16(tail, base + EOCD_CD_COUNT) == ZIP64_COUNT_SENTINEL) return true
            if (u32(tail, base + EOCD_CD_SIZE) == ZIP64_SENTINEL) return true
            return u32(tail, base + EOCD_CD_OFFSET) == ZIP64_SENTINEL
        }

        private fun parseDirectory(
            dir: ByteArray,
            count: Int,
        ): List<ZipEntryRange> {
            val out = ArrayList<ZipEntryRange>(count)
            var pos = 0
            repeat(count) {
                if (pos + CENTRAL_HEADER_SIZE > dir.size || sig(dir, pos) != CENTRAL_SIG) {
                    throw IOException("truncated central directory")
                }
                val method = u16(dir, pos + CD_METHOD)
                // Central-directory layout, not local-header: sizes sit at CD_COMP_SIZE /
                // CD_UNCOMP_SIZE (local uses +18/+22). Mixing them up reads crc as sizes.
                val compSize = u32(dir, pos + CD_COMP_SIZE)
                val uncompSize = u32(dir, pos + CD_UNCOMP_SIZE)
                val nameLen = u16(dir, pos + CD_NAME_LEN)
                val extraLen = u16(dir, pos + CD_EXTRA_LEN)
                val commentLen = u16(dir, pos + CD_COMMENT_LEN)
                val localOffset = u32(dir, pos + CD_LOCAL_OFFSET)
                if (compSize == ZIP64_SENTINEL || uncompSize == ZIP64_SENTINEL ||
                    localOffset == ZIP64_SENTINEL
                ) {
                    throw IOException("zip64 entry unsupported (TODO)")
                }
                val nameStart = pos + CENTRAL_HEADER_SIZE
                checkEntryBounds(dir, nameStart, nameLen, extraLen, commentLen)
                val name = String(dir, nameStart, nameLen, Charsets.UTF_8)
                pos = nameStart + nameLen + extraLen + commentLen
                if (name.endsWith("/")) return@repeat
                if (method != METHOD_STORED && method != METHOD_DEFLATED) return@repeat
                out.add(
                    ZipEntryRange(
                        name = name,
                        method = method,
                        compressedSize = compSize,
                        uncompressedSize = uncompSize,
                        localHeaderOffset = localOffset,
                    ),
                )
            }
            return out
        }

        // Lengths are untrusted: a run past the buffer is hostile input, not a long filename.
        private fun checkEntryBounds(
            dir: ByteArray,
            nameStart: Int,
            nameLen: Int,
            extraLen: Int,
            commentLen: Int,
        ) {
            if (nameLen + extraLen + commentLen > dir.size - nameStart) {
                throw IOException("central directory entry past end")
            }
        }
        private fun localDataOffset(reader: SeekableSmbReader, localOffset: Long): Long {
            val header = reader.readAt(localOffset, LOCAL_HEADER_SIZE)
            if (sig(header, 0) != LOCAL_SIG) throw IOException("bad local header")
            val nameLen = u16(header, LOCAL_NAME_LEN)
            val extraLen = u16(header, LOCAL_EXTRA_LEN)
            return localOffset + LOCAL_HEADER_SIZE + nameLen + extraLen
        }

        private const val EOCD_DISK_NO = 4
        private const val EOCD_CD_DISK = 6
        private const val EOCD_CD_COUNT = 8
        private const val EOCD_CD_SIZE = 12
        private const val EOCD_CD_OFFSET = 16
        private const val EOCD_COMMENT_LEN = 20
        private const val CD_METHOD = 10
        private const val CD_COMP_SIZE = 20
        private const val CD_UNCOMP_SIZE = 24
        private const val CD_NAME_LEN = 28
        private const val CD_EXTRA_LEN = 30
        private const val CD_COMMENT_LEN = 32
        private const val CD_LOCAL_OFFSET = 42
        private const val LOCAL_NAME_LEN = 26
        private const val LOCAL_EXTRA_LEN = 28
        private const val SIG_SIZE = 4
    }
}

/** Little-endian byte readers, shared by the ZIP parsers in this file. */
private fun sig(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and BYTE_MASK) or
        ((bytes[at + 1].toInt() and BYTE_MASK) shl BYTE_1_SHIFT) or
        ((bytes[at + 2].toInt() and BYTE_MASK) shl BYTE_2_SHIFT) or
        ((bytes[at + BYTE_INDEX_3].toInt() and BYTE_MASK) shl BYTE_3_SHIFT)

private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and BYTE_MASK) or
        ((bytes[at + 1].toInt() and BYTE_MASK) shl BYTE_1_SHIFT)

private fun u32(bytes: ByteArray, at: Int): Long =
    (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl BYTE_2_SHIFT)

private const val BYTE_MASK = 0xFF
private const val BYTE_1_SHIFT = 8
private const val BYTE_2_SHIFT = 16
private const val BYTE_3_SHIFT = 24
private const val BYTE_INDEX_3 = 3
