package com.absolutex.remote.smb

import java.io.IOException

/** One ZIP entry's byte ranges. All offsets are absolute file offsets. */
data class ZipEntryRange(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val dataOffset: Long,
)

/**
 * ZIP central-directory index built from ranged reads only: tail scan for EOCD, one read for
 * the directory, one small read per entry's local header. A 200 MB archive indexes in
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
        // Directory is metadata: larger means hostile input, not a comic (§2 degrade rule).
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 256L * 1024 * 1024

        @Throws(IOException::class)
        fun open(reader: SeekableSmbReader): ZipRemoteIndex {
            val size = reader.sizeBytes
            val tailSize = minOf(size, EOCD_TAIL_SIZE.toLong()).toInt()
            // A file shorter than EOCD_MIN_SIZE simply has no EOCD: same throw, no special case.
            val tail = reader.readAt(size - tailSize, tailSize)
            val eocd = parseEocd(tail)
                ?: throw IOException("no zip end-of-central-directory (or multi-disk/zip64 TODO)")
            if (eocd.cdSize > MAX_CENTRAL_DIRECTORY_BYTES ||
                eocd.cdOffset + eocd.cdSize > size
            ) {
                throw IOException("central directory past end")
            }
            val dir = reader.readAt(eocd.cdOffset, eocd.cdSize.toInt())
            return ZipRemoteIndex(parseDirectory(dir, eocd.cdCount, reader))
        }

        private data class Eocd(val cdCount: Int, val cdSize: Long, val cdOffset: Long)

        private fun parseEocd(tail: ByteArray): Eocd? {
            val base = findSignature(tail, EOCD_SIG) ?: return null
            // Flavors ranged reads cannot serve return null, not a half-parsed index:
            // multi-disk splits the directory across files, ZIP64 moves the counts elsewhere.
            if (isUnsupportedFlavor(tail, base)) return null
            return Eocd(
                u16(tail, base + EOCD_CD_COUNT),
                u32(tail, base + EOCD_CD_SIZE),
                u32(tail, base + EOCD_CD_OFFSET),
            )
        }

        private fun isUnsupportedFlavor(tail: ByteArray, base: Int): Boolean {
            if (u16(tail, base + EOCD_DISK_NO) != 0) return true
            if (u16(tail, base + EOCD_CD_DISK) != 0) return true
            if (u32(tail, base + EOCD_CD_SIZE) == ZIP64_SENTINEL) return true
            return u32(tail, base + EOCD_CD_OFFSET) == ZIP64_SENTINEL
        }

        private fun parseDirectory(
            dir: ByteArray,
            count: Int,
            reader: SeekableSmbReader,
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
                        dataOffset = localDataOffset(reader, localOffset),
                    ),
                )
            }
            return out
        }

        private fun localDataOffset(reader: SeekableSmbReader, localOffset: Long): Long {
            val header = reader.readAt(localOffset, LOCAL_HEADER_SIZE)
            if (sig(header, 0) != LOCAL_SIG) throw IOException("bad local header")
            val nameLen = u16(header, LOCAL_NAME_LEN)
            val extraLen = u16(header, LOCAL_EXTRA_LEN)
            return localOffset + LOCAL_HEADER_SIZE + nameLen + extraLen
        }

        private fun findSignature(bytes: ByteArray, signature: Int): Int? {
            // Backwards: the EOCD is the LAST record; a forward scan can hit a false positive
            // inside a stored entry's data.
            var i = bytes.size - SIG_SIZE
            while (i >= 0) {
                if (sig(bytes, i) == signature) return i
                i--
            }
            return null
        }

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
        private const val EOCD_DISK_NO = 4
        private const val EOCD_CD_DISK = 6
        private const val EOCD_CD_COUNT = 8
        private const val EOCD_CD_SIZE = 12
        private const val EOCD_CD_OFFSET = 16
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
        private const val BYTE_INDEX_3 = 3
    }
}
