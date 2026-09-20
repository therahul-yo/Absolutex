package com.absolutex.source.libarchive

/** Thin JNI surface. Stateless by design — see archive_jni.c. */
internal object LibArchive {
    init { System.loadLibrary("absolutex_archive") }

    /** Raw names in archive order. [complete] receives whether listing reached clean EOF. */
    @JvmStatic external fun nativeList(fd: Int, complete: BooleanArray): Array<ByteArray>?

    /** Data of the regular-file entry at [ordinal], as numbered by [nativeList]. */
    @JvmStatic external fun nativeExtract(fd: Int, ordinal: Int): ByteArray?
}
