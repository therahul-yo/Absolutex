package com.absolutex.remote.ftp

import com.absolutex.model.Page
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Streams CBZ pages off FTP through the ZIP central directory: index first, entry bytes later.
 *
 * Opening costs two small index transfers (EOCD tail + central directory), never a whole-file
 * pull; each page then costs its own exact range. STORED entries stream byte-for-byte, DEFLATED
 * entries inflate from the exact compressed range. Anything that is not a readable ZIP fails
 * fast with [IOException] instead of downloading.
 */
class FtpZipSource private constructor(
    private val reader: FtpSeekableReader,
    private val entries: List<FtpZipDirectory.Entry>,
    override val pages: List<Page>,
) : RemoteFtpSource {

    override fun openPage(index: Int): InputStream {
        val entry = entries.getOrNull(index) ?: throw IndexOutOfBoundsException(pageMessage(index))
        if (entry.compressedSize > ENTRY_MAX_BYTES || entry.uncompressedSize > ENTRY_MAX_BYTES) {
            throw IOException("entry too large: ${entry.name}")
        }
        val range = dataRange(entry)
        return decode(entry, reader.read(range.offset, range.length))
    }

    override fun openCover(): InputStream {
        val first = entries.firstOrNull() ?: throw IOException("empty archive has no cover")
        // Covers render at thumbnail scale; a giant first entry is treated as hostile, not art.
        if (first.uncompressedSize > COVER_MAX_BYTES) throw IOException("cover too large: ${first.name}")
        return openPage(0)
    }

    /** Owns no connection — the transport outlives each source. */
    override fun close() = Unit

    private fun dataRange(entry: FtpZipDirectory.Entry): DataRange {
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED) {
            throw IOException("unsupported ZIP method ${entry.method}: ${entry.name}")
        }
        // Sizes come from the central directory; the local header only locates the data start,
        // because entries written with a data descriptor leave zeroes in the local sizes.
        val header = reader.read(entry.localHeaderOffset, LF_FIXED)
        if (FtpZipDirectory.s32(header, 0) != LF_SIG) throw IOException("bad local header: ${entry.name}")
        val start = entry.localHeaderOffset + LF_FIXED +
            FtpZipDirectory.u16(header, LF_NAME_OFF) + FtpZipDirectory.u16(header, LF_EXTRA_OFF)
        return DataRange(start, entry.compressedSize.toInt())
    }

    private fun decode(entry: FtpZipDirectory.Entry, raw: ByteArray): InputStream {
        if (entry.method == METHOD_STORED) return ByteArrayInputStream(raw)
        val out = ByteArray(entry.uncompressedSize.toInt())
        // Inflater(true): ZIP stores raw RFC1951 deflate with no zlib header; the default fails.
        val inflater = Inflater(true)
        inflater.setInput(raw)
        try {
            var done = 0
            while (done < out.size && !inflater.finished()) {
                val count = inflater.inflate(out, done, out.size - done)
                if (count == EMPTY_INFLATE) break
                done += count
            }
            if (done != out.size) throw IOException("deflate size mismatch: ${entry.name}")
            return ByteArrayInputStream(out)
        } catch (expected: DataFormatException) {
            throw IOException("deflate error: ${entry.name}", expected)
        } finally {
            inflater.end()
        }
    }

    private fun pageMessage(index: Int): String = "page $index of ${pages.size}"

    private data class DataRange(val offset: Long, val length: Int)

    companion object {
        /** Largest single entry materialised; hostile archives love 4 GiB size fields. */
        const val ENTRY_MAX_BYTES = 33_554_432L

        /** Covers render small; a bigger first entry is treated as hostile, not fetched. */
        const val COVER_MAX_BYTES = 12_582_912L
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val LF_SIG = 0x04034B50
        private const val LF_FIXED = 30
        private const val LF_NAME_OFF = 26
        private const val LF_EXTRA_OFF = 28
        private const val EMPTY_INFLATE = 0

        /**
         * Opens [path] for streaming: index transfers only, no whole-file pull. Reuses
         * [EntryFilter] and [NaturalOrder] from `:source:api` so remote pages match local ones.
         */
        @Throws(IOException::class)
        fun open(transport: FtpTransport, path: String): FtpZipSource {
            val size = transport.sizeBytes(path)
            val reader = FtpSeekableReader(transport, path, size)
            val kept = FtpZipDirectory.open(reader::read, size)
                .filter { EntryFilter.isPage(it.name) }
                // Stable sort: entries with identical names keep their archive order.
                .sortedWith(compareBy(NaturalOrder) { it.name })
            val pages = kept.mapIndexed { index, entry ->
                Page(index = index, entryName = entry.name, sizeBytes = entry.uncompressedSize)
            }
            return FtpZipSource(reader, kept, pages)
        }
    }
}
