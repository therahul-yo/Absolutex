package com.absolutex.source.libarchive

/** Thin JNI surface. Stateless by design — see archive_jni.c. */
internal object LibArchive {
    init { System.loadLibrary("absolutex_archive") }

    @JvmStatic external fun nativeList(fd: Int): Array<String>?
    @JvmStatic external fun nativeExtract(fd: Int, name: String): ByteArray?
}
