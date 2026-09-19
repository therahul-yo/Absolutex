package com.absolutex.remote.cloud

/**
 * A valid STORED ZIP of arbitrary size that is never materialised.
 *
 * The 300 MB proof needs a 300 MB file and must not allocate one — a test that holds the whole
 * archive on the heap cannot honestly claim the transport avoided transferring it. Headers, the
 * central directory and the EOCD are real bytes (a few KB in total); entry payloads are computed
 * from their offset on demand, so any range can be served without the rest existing.
 *
 * CRCs are zero: [com.absolutex.remote.core.ZipDirectory] does not verify them and
 * `CoreComicSource` returns STORED bytes as-is, so computing them would cost 300 MB of hashing
 * to assert nothing.
 */
internal class SparseZip(private val entryCount: Int, private val entryBytes: Int) {

    private class Segment(val start: Long, val size: Int, val bytes: ByteArray?, val entry: Int)

    private val segments = ArrayList<Segment>()
    val totalSize: Long

    init {
        var cursor = 0L
        val localOffsets = LongArray(entryCount)
        repeat(entryCount) { index ->
            localOffsets[index] = cursor
            val header = localHeader(index)
            segments += Segment(cursor, header.size, header, -1)
            cursor += header.size
            segments += Segment(cursor, entryBytes, null, index)
            cursor += entryBytes.toLong()
        }
        val cdStart = cursor
        repeat(entryCount) { index ->
            val record = centralRecord(index, localOffsets[index])
            segments += Segment(cursor, record.size, record, -1)
            cursor += record.size
        }
        val eocd = eocd((cursor - cdStart).toInt(), cdStart)
        segments += Segment(cursor, eocd.size, eocd, -1)
        totalSize = cursor + eocd.size
    }

    fun nameOf(index: Int): String = "page%03d.jpg".format(index)

    /** The byte this archive holds at [position] inside entry [entry]'s payload. */
    private fun payloadByte(entry: Int, position: Int): Byte = ((entry * 31 + position) % 251).toByte()

    /** Entry [index]'s payload in full — for asserting a page arrived intact. */
    fun payloadOf(index: Int): ByteArray = ByteArray(entryBytes) { payloadByte(index, it) }

    /** Any slice of the archive, materialising only what is asked for. */
    fun range(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "bad range $offset+$length" }
        require(offset + length <= totalSize) { "range past end: $offset+$length of $totalSize" }
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val position = offset + done
            val segment = segments.first { position >= it.start && position < it.start + it.size }
            val within = (position - segment.start).toInt()
            val take = minOf(length - done, segment.size - within)
            if (segment.bytes != null) {
                segment.bytes.copyInto(out, done, within, within + take)
            } else {
                for (i in 0 until take) out[done + i] = payloadByte(segment.entry, within + i)
            }
            done += take
        }
        return out
    }

    private fun localHeader(index: Int): ByteArray {
        val name = nameOf(index).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(LOCAL_HEADER_LEN + name.size)
        le32(out, 0, LOCAL_SIG)
        le16(out, 4, VERSION_NEEDED)
        le16(out, 8, METHOD_STORED)
        le32(out, 18, entryBytes.toLong())
        le32(out, 22, entryBytes.toLong())
        le16(out, 26, name.size)
        name.copyInto(out, LOCAL_HEADER_LEN)
        return out
    }

    private fun centralRecord(index: Int, localOffset: Long): ByteArray {
        val name = nameOf(index).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(CENTRAL_HEADER_LEN + name.size)
        le32(out, 0, CENTRAL_SIG)
        le16(out, 6, VERSION_NEEDED)
        le16(out, 10, METHOD_STORED)
        le32(out, 20, entryBytes.toLong())
        le32(out, 24, entryBytes.toLong())
        le16(out, 28, name.size)
        le32(out, 42, localOffset)
        name.copyInto(out, CENTRAL_HEADER_LEN)
        return out
    }

    private fun eocd(cdSize: Int, cdOffset: Long): ByteArray {
        val out = ByteArray(EOCD_LEN)
        le32(out, 0, EOCD_SIG)
        le16(out, 8, entryCount)
        le16(out, 10, entryCount)
        le32(out, 12, cdSize.toLong())
        le32(out, 16, cdOffset)
        return out
    }

    private fun le16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun le32(out: ByteArray, at: Int, value: Long) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        out[at + 2] = ((value ushr 16) and 0xFF).toByte()
        out[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private companion object {
        const val LOCAL_SIG = 0x04034B50L
        const val CENTRAL_SIG = 0x02014B50L
        const val EOCD_SIG = 0x06054B50L
        const val LOCAL_HEADER_LEN = 30
        const val CENTRAL_HEADER_LEN = 46
        const val EOCD_LEN = 22
        const val VERSION_NEEDED = 20
        const val METHOD_STORED = 0
    }
}
