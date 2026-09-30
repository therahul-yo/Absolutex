package com.absolutex.source.libarchive

import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/** Test doubles for the solid cache: an archive that is only entries in a list, and a way to run it. */
internal class FakeStreamer(
    private val entries: List<ByteArray?>,
    private val end: PassEnd = PassEnd.COMPLETE,
    private val beforeEntry: (Int) -> Unit = {},
) : EntryStreamer {
    val calls = AtomicInteger()
    val delivered = AtomicInteger()

    override fun stream(wanted: BooleanArray, maxBytes: Long, sink: EntrySink): PassEnd {
        calls.incrementAndGet()
        for ((ordinal, data) in entries.withIndex()) {
            if (!wanted[ordinal]) continue
            beforeEntry(ordinal)
            delivered.incrementAndGet()
            if (!sink.onEntry(ordinal, data)) return PassEnd.STOPPED
        }
        return end
    }
}

/** Threads started by [SolidRig.threadedLaunch], so a test can wait for the pass to end. */
internal class SolidRig(val root: File, val key: String = "0123456789abcdef0123456789abcdef") {
    val threads = mutableListOf<Thread>()
    val spawned = AtomicInteger()
    val config get() = SolidCacheConfig(root, key)
    val store get() = SolidCacheStore(root)

    fun inlineLaunch(
        streamer: EntryStreamer,
        probe: SolidProbe = SOLID,
        free: Long = FREE,
        writer: PageWriter = PageWriter.Default,
    ) = SolidLaunch({ probe }, streamer, { free }, { spawned.incrementAndGet(); it.run() }, writer)

    fun threadedLaunch(
        streamer: EntryStreamer,
        probe: SolidProbe = SOLID,
        writer: PageWriter = PageWriter.Default,
    ) = SolidLaunch(
        { probe }, streamer, { FREE },
        { spawned.incrementAndGet(); threads += Thread(it).also(Thread::start) }, writer,
    )

    fun joinPass() = threads.forEach { it.join(JOIN_MS) }

    companion object {
        val SOLID = SolidProbe(solid = true, totalBytes = 64L shl 20)
        const val FREE = 64L shl 30
        private const val JOIN_MS = 10_000L
    }
}

/** Entry i is the bytes `i, i, i...` (length [size]), so a wrong page is visible in one byte. */
internal fun pageBytes(count: Int, size: Int = 16): List<ByteArray?> =
    List(count) { page -> ByteArray(size) { page.toByte() } }

internal fun SolidRead.bytes(): ByteArray = when (this) {
    is SolidRead.Hit -> stream.use(InputStream::readBytes)
    else -> throw AssertionError("expected a hit, got $this")
}

internal fun bookOf(pages: Int, withInfo: Boolean = false): SolidBook = SolidBook(
    entryCount = pages + if (withInfo) 1 else 0,
    pageOrdinals = IntArray(pages) { it },
    infoOrdinal = if (withInfo) pages else -1,
)
