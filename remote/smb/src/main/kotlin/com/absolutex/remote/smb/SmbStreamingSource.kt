package com.absolutex.remote.smb

import com.absolutex.model.Page
import com.absolutex.remote.core.SeekableReader
import com.absolutex.remote.core.ZipDirectory
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * ZIP/CBZ over a core [SeekableReader]: index from the tail, pages from entry ranges.
 * STORED entries stream straight through the block cache; DEFLATED entries fetch exactly
 * their compressed range and inflate in memory — still no whole-archive download either way.
 *
 * Non-ZIP containers (RAR/7z/TAR) are rejected with a message: solid archives cannot
 * range-extract without a full index walk, and that walk lives in the lead-owned native
 * bridge. TODO(remote): bounded full-file cache fallback once the bridge offers an
 * fd-backed handoff (see RemoteComicSource TODO). Stated plainly: streaming here is
 * ZIP/CBZ-only; anything else fails fast instead of pretending.
 */
class SmbStreamingSource private constructor(
    private val reader: SeekableReader,
    private val entries: List<ZipDirectory.Entry>,
    override val pages: List<Page>,
) : RemoteComicSource {

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        val entry = entries[page.index]
        checkEntryReadable(entry)
        // Local header offsets resolve here, per opened page — indexing never pays a
        // ranged read per entry (about 600 round trips for a 200-page CBZ).
        val dataOffset = ZipDirectory.localDataOffsetOf(reader::readAt, entry)
        return when (entry.method) {
            ZipDirectory.METHOD_STORED ->
                reader.openStream(dataOffset, entry.compressedSize)
            else ->
                DeflateStream(
                    reader.readAt(dataOffset, entry.compressedSize.toInt()),
                )
        }
    }

    private fun checkEntryReadable(entry: ZipDirectory.Entry) {
        if (entry.compressedSize > MAX_REMOTE_ENTRY_BYTES) {
            throw IOException("entry too large: ${entry.compressedSize} bytes")
        }
        if (entry.method != ZipDirectory.METHOD_STORED && entry.method != ZipDirectory.METHOD_DEFLATED) {
            throw IOException("unsupported ZIP method ${entry.method}: ${entry.name}")
        }
    }

    override fun close() {
        // Pages hold no state; dropping cached blocks releases the working set on book close.
        // (The caller owns the transport lifecycle and closes it separately.)
        reader.evictAll()
    }

    companion object {
        // A page is a few megabytes; larger means hostile input, not a scan (§2 degrade rule).
        const val MAX_REMOTE_ENTRY_BYTES = 32L * 1024 * 1024

        @Throws(IOException::class)
        fun open(transport: SmbTransport, remotePath: String): SmbStreamingSource {
            val reader = SeekableReader(transport.bind(remotePath), transport.sizeBytes(remotePath))
            if (!isZip(reader)) {
                throw IOException("remote streaming supports zip/cbz only (TODO: cached fallback)")
            }
            val entries = ZipDirectory.open(reader::readAt, reader.sizeBytes)
                .filter { EntryFilter.isPage(it.name) }
                .filter { it.method == ZipDirectory.METHOD_STORED || it.method == ZipDirectory.METHOD_DEFLATED }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            val pages = entries.mapIndexed { i, entry ->
                Page(index = i, entryName = entry.name, sizeBytes = entry.uncompressedSize)
            }
            // entries is parallel to pages: filter/sort produce the view, entries stay attached.
            return SmbStreamingSource(reader, entries, pages)
        }

        private fun isZip(reader: SeekableReader): Boolean {
            if (reader.sizeBytes < MIN_ZIP_SIZE) return false
            val magic = reader.readAt(0, MIN_ZIP_SIZE)
            return magic[0] == PK_BYTE_0 && magic[1] == PK_BYTE_1 &&
                magic[HEADER_INDEX_2] == LOCAL_FILE_SIG_2 && magic[HEADER_INDEX_3] == LOCAL_FILE_SIG_3
        }

        private const val MIN_ZIP_SIZE = 4
        private const val HEADER_INDEX_2 = 2
        private const val HEADER_INDEX_3 = 3
        private const val PK_BYTE_0 = 0x50.toByte()
        private const val PK_BYTE_1 = 0x4B.toByte()
        private const val LOCAL_FILE_SIG_2 = 0x03.toByte()
        private const val LOCAL_FILE_SIG_3 = 0x04.toByte()
    }
}

/**
 * Raw-DEFLATE entry bytes with an owned [Inflater]. InflaterInputStream does not end a
 * caller-supplied inflater on close, which leaks native zlib state per DEFLATED page —
 * so this stream owns it and ends it, whatever path close takes.
 */
internal class DeflateStream(
    data: ByteArray,
    private val inflater: Inflater = Inflater(true),
) : InputStream() {
    private val inner = InflaterInputStream(ByteArrayInputStream(data), inflater)
    private var closed = false

    override fun read(): Int = inner.read()

    override fun read(buffer: ByteArray, off: Int, len: Int): Int = inner.read(buffer, off, len)

    override fun close() {
        if (closed) return
        closed = true
        try {
            inner.close()
        } finally {
            inflater.end()
        }
    }
}
