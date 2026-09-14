package com.absolutex.source.libarchive

/** Thin JNI surface. Stateless by design — see archive_jni.c. */
internal object LibArchive {
    init { System.loadLibrary("absolutex_archive") }

    /** Raw name bytes of every regular-file entry, in archive order. Index = ordinal. */
    @JvmStatic external fun nativeList(fd: Int): Array<ByteArray>?

    /** Data of the regular-file entry at [ordinal], as numbered by [nativeList]. */
    @JvmStatic external fun nativeExtract(fd: Int, ordinal: Int): ByteArray?
}
