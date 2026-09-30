package com.absolutex.source.libarchive

import com.absolutex.model.ComicInfo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.BitSet
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How one read was answered by the cache. */
internal sealed interface SolidRead {
    /** The entry, served from its file with no decoding. */
    class Hit(val stream: InputStream) : SolidRead

    /** The archive cannot give this entry: the same verdict a direct read reaches. */
    data object Torn : SolidRead

    /** The cache cannot help (abandoned, or the file is gone): read the archive directly. */
    data object Fallback : SolidRead
}

/**
 * Which entries a solid pass has written so far, and the blocking read over them.
 *
 * A read of an entry the pass has not reached yet WAITS, so a page turn ahead of the pass costs
 * the rest of the one pass, once, and never a second decode. The wait is interruptible: a
 * cancelled coroutine (`runInterruptible`) or a closed source ends it at once, so an abandoned
 * jump cannot hold a decode thread.
 */
internal class SolidPages(private val dir: File) {
    private enum class Phase { RUNNING, FINISHED, ABANDONED }

    private enum class Verdict { READY, TORN, FALLBACK }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ready = BitSet()
    private val torn = BitSet()
    private var phase = Phase.RUNNING
    private var closed = false

    /** The book's ComicInfo, once the pass has met and parsed it. */
    @Volatile
    var comicInfo: ComicInfo? = null

    val isClosed: Boolean get() = lock.withLock { closed }

    /** Generated from the ordinal alone: an entry's own name never reaches the file system. */
    fun fileFor(ordinal: Int): File = File(dir, ordinal.toString().padStart(NAME_DIGITS, '0') + ".bin")

    fun markReady(ordinal: Int) = lock.withLock {
        ready.set(ordinal)
        changed.signalAll()
    }

    fun markTorn(ordinal: Int) = lock.withLock {
        torn.set(ordinal)
        changed.signalAll()
    }

    /** The pass reached its end: an entry it never delivered is one the archive cannot give. */
    fun finish() = lock.withLock {
        if (phase == Phase.RUNNING) phase = Phase.FINISHED
        changed.signalAll()
    }

    /** The pass was given up: nothing here is trusted, and every read goes to the archive. */
    fun abandon() = lock.withLock {
        phase = Phase.ABANDONED
        ready.clear()
        torn.clear()
        changed.signalAll()
    }

    /** Wakes every waiting read with an error and refuses new ones. */
    fun close() = lock.withLock {
        closed = true
        changed.signalAll()
    }

    /**
     * The entry at [ordinal], waiting for the pass to produce it when it has not yet.
     *
     * @throws IOException when the source is closed.
     * @throws CancellationException when the waiting thread is interrupted.
     */
    fun read(ordinal: Int): SolidRead {
        val verdict = lock.withLock {
            var v = verdictLocked(ordinal)
            while (v == null) {
                awaitChange()
                v = verdictLocked(ordinal)
            }
            v
        }
        return when (verdict) {
            Verdict.READY -> open(ordinal)
            Verdict.TORN -> SolidRead.Torn
            Verdict.FALLBACK -> SolidRead.Fallback
        }
    }

    /** Null means "not yet": the caller waits. Must hold [lock]. */
    private fun verdictLocked(ordinal: Int): Verdict? {
        if (closed) throw IOException("Archive source is closed")
        return when {
            ready[ordinal] -> Verdict.READY
            torn[ordinal] -> Verdict.TORN
            phase == Phase.RUNNING -> null
            phase == Phase.FINISHED -> Verdict.TORN
            else -> Verdict.FALLBACK
        }
    }

    private fun awaitChange() {
        try {
            changed.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("page read cancelled").apply { initCause(e) }
        }
    }

    /** A file that is gone (evicted, purged by the system, or discarded by a racing abandon) falls back. */
    private fun open(ordinal: Int): SolidRead =
        runCatching { FileInputStream(fileFor(ordinal)) }
            .fold(onSuccess = { SolidRead.Hit(it) }, onFailure = { SolidRead.Fallback })

    private companion object {
        const val NAME_DIGITS = 5
    }
}
