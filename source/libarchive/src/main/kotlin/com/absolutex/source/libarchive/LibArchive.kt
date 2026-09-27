package com.absolutex.source.libarchive

/** Thin JNI surface. Stateless by design — see archive_jni.c. */
internal object LibArchive {
    init { System.loadLibrary("absolutex_archive") }

    /** Raw names in archive order. [complete] receives whether listing reached clean EOF. */
    @JvmStatic external fun nativeList(
        fd: Int,
        complete: BooleanArray,
        encrypted: BooleanArray,
        passphrase: ByteArray?,
    ): Array<ByteArray>?

    /** Data of the regular-file entry at [ordinal], as numbered by [nativeList]. */
    @JvmStatic external fun nativeExtract(fd: Int, ordinal: Int, passphrase: ByteArray?): ByteArray?

    /**
     * Data of [count] consecutive entries starting at [fromOrdinal], in one header walk.
     *
     * The ordinal contract is identical to [nativeExtract]'s — both count through the same
     * C-side is_ordinal_entry — so a window and a loop of single extracts must agree entry
     * for entry. Element `i` is the entry at `fromOrdinal + i`, and is null when that one
     * entry is absent or unreadable: the same per-entry verdict [nativeExtract] gives, so
     * the caller cannot tell which path produced it.
     *
     * Returns null when the archive itself cannot be read at all, matching [nativeExtract].
     *
     * Exists because [nativeExtract] re-walks every header from zero on each call, and a
     * prefetch window is a contiguous run. Measured on the 300-page 6 MP corpus, reaching
     * page 299 costs 1.62 ms more than page 0 — 34% of a first page, paid per window.
     */
    @JvmStatic external fun nativeExtractWindow(
        fd: Int,
        fromOrdinal: Int,
        count: Int,
        passphrase: ByteArray?,
    ): Array<ByteArray?>?
}
