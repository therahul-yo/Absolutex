package com.absolutex.source.libarchive

/**
 * Receives an archive's entries, in archive order, from ONE sequential decode.
 *
 * Called from native code ([LibArchive.nativeStreamEntries]) on the pass thread, so the method
 * name and signature are fixed by archive_jni.c and kept by consumer-rules.pro.
 */
internal interface EntrySink {
    /**
     * [data] is the whole entry, or null when the archive could not give it (a torn entry costs
     * that page, nothing else). Return false to stop the pass: the book was closed, or the cache
     * could not take the entry.
     */
    fun onEntry(ordinal: Int, data: ByteArray?): Boolean
}

/** How a pass ended. The order is the native side's PASS_* codes, read by value. */
internal enum class PassEnd {
    /** Every wanted entry was delivered. */
    COMPLETE,

    /** The sink stopped it. */
    STOPPED,

    /** The archive broke first: the entries after that point were never delivered. */
    FAILED,

    /** The byte ceiling was reached. */
    LIMIT,

    ;

    companion object {
        fun fromCode(code: Int): PassEnd = entries.getOrElse(code) { FAILED }
    }
}

/**
 * The seam between the cache and the archive: yields the wanted entries in archive order, once.
 * Production is [NativeSolidArchive]; the tests use a fake, which is how the cache logic is proved
 * without an archive.
 */
internal fun interface EntryStreamer {
    /**
     * Streams every entry whose ordinal is true in [wanted] to [sink], stopping when the sink says
     * so or when the entries walked and delivered add up to more than [maxBytes].
     * Throws when the archive cannot be opened at all.
     */
    fun stream(wanted: BooleanArray, maxBytes: Long, sink: EntrySink): PassEnd
}
