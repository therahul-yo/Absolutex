package com.absolutex.source.libarchive

import com.absolutex.source.ComicInfoParser
import java.io.File
import java.io.IOException

/** The largest ComicInfo.xml read, the same cap the direct path uses. */
internal const val SOLID_INFO_MAX_BYTES = 1024 * 1024

/** What a pass is to do: which ordinals to write, how much it may write, and which one is ComicInfo. */
internal class PassPlan(val wanted: BooleanArray, val infoOrdinal: Int, val budgetBytes: Long)

/** Writes one entry's bytes; the seam a disk-full test replaces. */
internal fun interface PageWriter {
    @Throws(IOException::class)
    fun write(file: File, data: ByteArray)

    companion object {
        /** Writes beside the target and renames into place, so a file that exists is a whole file. */
        val Default = PageWriter { file, data ->
            val part = File(file.path + ".part")
            try {
                part.writeBytes(data)
                if (!part.renameTo(file)) throw IOException("could not publish a cached page")
            } finally {
                part.delete()
            }
        }
    }
}

/** How a pass ended, from the cache's point of view. */
internal enum class PassOutcome {
    /** Every wanted entry is on disk. */
    CACHED,

    /** The archive broke part-way or held a torn entry: what was written is served, the rest is unreadable. */
    BROKEN,

    /** Given up (byte ceiling, a write failed, an unexpected error): reads go to the archive. */
    ABANDONED,

    /** The source was closed first. */
    CLOSED,
}

/**
 * The one sequential decode of a solid archive, writing each wanted entry to the cache as it
 * arrives. Runs on its own thread ([run]); everything it learns goes through [pages].
 *
 * It stops within one entry of the source closing (the sink answers false), leaving the native
 * side to release its descriptor on the way out.
 */
internal class SolidPass(
    private val pages: SolidPages,
    private val plan: PassPlan,
    private val streamer: EntryStreamer,
    private val writer: PageWriter,
    private val finished: (PassOutcome) -> Unit,
) : EntrySink, Runnable {
    private var written = 0L
    private var gaveUp = false
    private var tornSeen = false
    private var delivered = false

    override fun onEntry(ordinal: Int, data: ByteArray?): Boolean {
        if (pages.isClosed) return false
        delivered = true
        if (data == null) {
            tornSeen = true
            pages.markTorn(ordinal)
            return true
        }
        written += data.size
        gaveUp = written > plan.budgetBytes || !store(ordinal, data)
        return !gaveUp
    }

    /**
     * Disk full or a directory that vanished ends the cache rather than failing anything: it is an
     * optimisation, and the caller reads the archive directly instead.
     */
    private fun store(ordinal: Int, data: ByteArray): Boolean {
        if (runCatching { writer.write(pages.fileFor(ordinal), data) }.isFailure) return false
        if (ordinal == plan.infoOrdinal) pages.comicInfo = parseInfo(data)
        pages.markReady(ordinal)
        return true
    }

    private fun parseInfo(data: ByteArray) = data.takeIf { it.size <= SOLID_INFO_MAX_BYTES }
        ?.let { runCatching { ComicInfoParser.parse(it.inputStream()) }.getOrNull() }

    /** A background thread must not die unaccounted for: any failure of the stream is "give up". */
    override fun run() {
        val end = runCatching { streamer.stream(plan.wanted, plan.budgetBytes, this) }.getOrNull()
        finished(outcomeOf(end))
    }

    private fun outcomeOf(end: PassEnd?): PassOutcome = when {
        pages.isClosed -> PassOutcome.CLOSED
        gaveUp || end == null || end == PassEnd.LIMIT || end == PassEnd.STOPPED -> PassOutcome.ABANDONED
        // Failing before anything arrived (open, allocation or guard failure) says nothing about the
        // pages: the direct read gets its own try rather than every page being called torn.
        end == PassEnd.FAILED && !delivered -> PassOutcome.ABANDONED
        // A torn entry has no file, so a cache with one is served this session but never kept.
        end == PassEnd.COMPLETE && !tornSeen -> PassOutcome.CACHED
        else -> PassOutcome.BROKEN
    }
}
