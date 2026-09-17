package com.absolutex.remote.core

import com.absolutex.model.BookIdentity
import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * ZIP/CBZ over a core [SeekableReader], as a [ComicSource] the reader already knows.
 *
 * Traffic discipline (asserted with a counting transport): opening indexes from the tail
 * plus the central directory only; a page fetches its local header plus its entry bytes;
 * opened pages sit in a byte-bounded LRU, so paging back costs zero transport calls.
 * Thread-safe per the ComicSource contract: the decode pool fans pages out across cores.
 *
 * Cancellation: [close] latches — every later transport read throws instead of running,
 * the page cache drops, and already-open streams keep only their materialised bytes (no
 * handles escape this class). The transport itself closes outside every lock, so a
 * stalled in-flight read never holds up closing the book; a call already blocked inside
 * the transport is bounded by that transport's own timeouts (SMB/FTP both configure
 * connect and socket timeouts), and in-flight bytes discard on the next [ensureOpen].
 */
class CoreComicSource private constructor(
    private val transport: RangeTransport,
    private val reader: SeekableReader,
    private val entries: List<ZipDirectory.Entry>,
    override val pages: List<Page>,
) : ComicSource {

    private val cacheLock = Any()

    @Volatile
    private var closed = false

    private val pageCache = LinkedHashMap<Int, ByteArray>(CACHE_SLOTS, LOAD_FACTOR, true)
    private var cachedBytes = 0L

    override fun openPage(index: Int): InputStream {
        val page = pages.getOrNull(index)
            ?: throw IndexOutOfBoundsException("page $index of ${pages.size}")
        synchronized(cacheLock) {
            pageCache[page.index]?.let { return ByteArrayInputStream(it) }
        }
        // No single-flight: two threads opening the same uncached page fetch it twice. On
        // a LAN that duplicates one ranged read instead of serialising every decode behind
        // a shared in-flight map.
        val data = fetchPage(entries[page.index])
        cachePage(page.index, data)
        return ByteArrayInputStream(data)
    }

    override fun openCover(): InputStream {
        if (pages.isEmpty()) throw IOException("archive has no pages")
        return openPage(0)
    }

    override fun close() {
        synchronized(cacheLock) {
            if (closed) return
            closed = true
            pageCache.clear()
            cachedBytes = 0L
        }
        // Outside every lock, exactly once: a backend close is a session teardown round
        // trip, and an in-flight stalled read must never hold up closing the book. A
        // failing close must not fail the book close, so it is contained, not propagated.
        runCatching { transport.close() }
    }

    private fun fetchPage(entry: ZipDirectory.Entry): ByteArray {
        checkEntrySizes(entry)
        val dataOffset = ZipDirectory.localDataOffsetOf(::readChecked, entry)
        ensureOpen()
        val data = reader.readAt(dataOffset, entry.compressedSize.toInt())
        return if (entry.method == ZipDirectory.METHOD_STORED) {
            data
        } else {
            inflateEntry(data, entry)
        }
    }

    /**
     * Caches one opened page under a byte budget, evicting least-recently-used first. The
     * inflation cap bounds every page, so eviction always terminates with room: no single
     * page can outgrow the budget alone.
     */
    private fun cachePage(index: Int, data: ByteArray) {
        synchronized(cacheLock) {
            val previous = pageCache.put(index, data)
            cachedBytes += data.size.toLong() - (previous?.size ?: 0)
            val eldest = pageCache.entries.iterator()
            while (cachedBytes > MAX_CACHED_BYTES && eldest.hasNext()) {
                val victim = eldest.next()
                eldest.remove()
                cachedBytes -= victim.value.size.toLong()
            }
        }
    }

    private fun readChecked(offset: Long, length: Int): ByteArray {
        ensureOpen()
        return reader.readAt(offset, length)
    }

    private fun ensureOpen() {
        if (closed) throw IOException("remote book closed")
    }

    companion object {
        /** A page is a few megabytes; larger means hostile input, not a scan (§2 degrade rule). */
        const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

        /** Hard cap on one page's inflated bytes; the declared size is enforced exactly. */
        const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

        /**
         * Opened pages kept for zero-cost back-navigation, evicted least-recently-used
         * first past this many bytes. Sized to hold several pages without approaching the
         * reader's own tile budget.
         */
        const val MAX_CACHED_BYTES = 64L * 1024 * 1024

        private const val MAGIC_SIZE = 4
        private const val ZIP_MAGIC_0 = 0x50
        private const val ZIP_MAGIC_1 = 0x4B
        private const val ZIP_MAGIC_2 = 0x03
        private const val ZIP_MAGIC_3 = 0x04
        private const val INFLATE_CHUNK_BYTES = 8 * 1024
        private const val INFLATE_START_BYTES = 64 * 1024L
        private const val CACHE_SLOTS = 16
        private const val LOAD_FACTOR = 0.75f

        @Throws(IOException::class)
        fun open(transport: RangeTransport, displayName: String): RemoteOpenResult {
            val size = transport.sizeBytes()
            sniffReason(transport, size)?.let { return RemoteOpenResult.DownloadRequired(it) }
            val reader = SeekableReader(transport, size)
            val entries = ZipDirectory.open(reader::readAt, size)
                .filter { EntryFilter.isPage(it.name) }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            val pages = entries.mapIndexed { index, entry ->
                Page(index = index, entryName = entry.name, sizeBytes = entry.uncompressedSize)
            }
            val source = CoreComicSource(transport, reader, entries, pages)
            return RemoteOpenResult.Ready(source, BookIdentity.of(displayName, size), displayName, size)
        }

        /**
         * ZIP streams by central directory. Everything else — RAR (index walk needs the
         * native bridge), 7z solid (random access impossible without full decode), TAR
         * (index needs a full sequential scan), non-archives — needs the whole file first.
         */
        private fun sniffReason(transport: RangeTransport, size: Long): String? {
            if (size < MAGIC_SIZE) return "file too small for an archive — download for offline"
            return if (!isZipMagic(transport.readAt(0, MAGIC_SIZE))) {
                "remote streaming is ZIP/CBZ-only (CBR/CB7/CBT need a full-file index) " +
                    "— download for offline"
            } else {
                null
            }
        }

        private fun isZipMagic(magic: ByteArray): Boolean {
            val header = magic[0].toInt() and BYTE_MASK == ZIP_MAGIC_0 &&
                magic[1].toInt() and BYTE_MASK == ZIP_MAGIC_1
            val kind = magic[BYTE_INDEX_2].toInt() and BYTE_MASK == ZIP_MAGIC_2 &&
                magic[BYTE_INDEX_3].toInt() and BYTE_MASK == ZIP_MAGIC_3
            return header && kind
        }

        private fun checkEntrySizes(entry: ZipDirectory.Entry) {
            if (entry.compressedSize > MAX_ENTRY_BYTES) {
                throw IOException("entry too large: ${entry.compressedSize} bytes")
            }
            checkDeclaredSize(entry.uncompressedSize)
        }

        private fun checkDeclaredSize(declared: Long) {
            if (declared > MAX_DECOMPRESSED_BYTES) {
                throw IOException("entry too large when inflated: $declared bytes")
            }
        }

        /**
         * Raw-DEFLATE entry bytes with an exact output bound: a valid entry inflates to
         * exactly its declared size, so anything beyond is a bomb or a corrupt stream.
         * The owned inflater always ends — native zlib state must not leak per page.
         */
        private fun inflateEntry(data: ByteArray, entry: ZipDirectory.Entry): ByteArray {
            val declared = entry.uncompressedSize
            checkDeclaredSize(declared)
            if (data.isEmpty() && declared == 0L) return ByteArray(0)
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(minOf(declared, INFLATE_START_BYTES).toInt())
                val chunk = ByteArray(INFLATE_CHUNK_BYTES)
                var total = 0L
                while (!inflater.finished()) {
                    if (inflater.needsInput()) throw IOException("truncated deflated entry")
                    val count = inflateOnce(inflater, chunk, entry.name)
                    total += count
                    if (total > declared) throw IOException("decompressed output past declared size")
                    out.write(chunk, 0, count)
                }
                ensureExactSize(entry, total, declared)
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        /** One inflation step: corrupt DEFLATE surfaces as IOException, never unchecked. */
        private fun inflateOnce(inflater: Inflater, chunk: ByteArray, entryName: String): Int {
            try {
                return inflater.inflate(chunk)
            } catch (e: DataFormatException) {
                throw IOException("deflate error: $entryName", e)
            }
        }

        /** A stream that ends cleanly short of its declared size is corrupt, not short. */
        private fun ensureExactSize(entry: ZipDirectory.Entry, total: Long, declared: Long) {
            if (total < declared) throw IOException("decompressed output shorter than declared: ${entry.name}")
        }

        private const val BYTE_MASK = 0xFF
        private const val BYTE_INDEX_2 = 2
        private const val BYTE_INDEX_3 = 3
    }
}
