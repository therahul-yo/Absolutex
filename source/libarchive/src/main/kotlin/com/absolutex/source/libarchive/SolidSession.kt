package com.absolutex.source.libarchive

import com.absolutex.model.ComicInfo
import java.io.Closeable

/**
 * One open source's hold on its archive's cache: the pages, the pass filling them (if any), and the
 * cleanup that must happen exactly once when the source closes and the pass has stopped.
 *
 * A finished cache stays on disk (touched, so it is the last to be evicted). Anything else is
 * deleted: an unfinished directory must never be mistaken for a complete one.
 */
internal class SolidSession(
    private val store: SolidCacheStore,
    private val key: String,
    private val entryCount: Int,
    private val pages: SolidPages,
    reused: Boolean,
) : Closeable {
    private var passRunning = false
    private var complete = reused
    private var closed = false

    val comicInfo: ComicInfo? get() = pages.comicInfo

    /** May wait for the pass; see [SolidPages.read]. */
    fun read(ordinal: Int): SolidRead = pages.read(ordinal)

    /** Runs [pass] through [spawn]; false when it could not be started (the session is then closed). */
    @Synchronized
    fun start(pass: Runnable, spawn: (Runnable) -> Unit): Boolean {
        passRunning = true
        if (runCatching { spawn(pass) }.isSuccess) return true
        passRunning = false
        pages.abandon()
        closeLocked()
        return false
    }

    /** The pass thread's last act. */
    @Synchronized
    fun onPassEnd(outcome: PassOutcome) {
        passRunning = false
        when (outcome) {
            PassOutcome.CACHED -> {
                complete = runCatching { store.markComplete(key, entryCount) }.isSuccess
                pages.finish()
            }
            PassOutcome.BROKEN -> pages.finish()
            PassOutcome.ABANDONED -> {
                pages.abandon()
                store.discard(key)
            }
            PassOutcome.CLOSED -> Unit
        }
        if (closed) cleanup()
    }

    @Synchronized
    override fun close() = closeLocked()

    /** Wakes readers, tells the pass to stop, and cleans up now if no pass is left to stop. */
    private fun closeLocked() {
        if (closed) return
        closed = true
        pages.close()
        if (!passRunning) cleanup()
    }

    private fun cleanup() {
        if (complete) store.touch(key) else store.discard(key)
        store.release(key)
    }
}
