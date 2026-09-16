package com.absolutex.core.thumbnails

import java.io.File

/**
 * Raw file operations for [ThumbDiskCache]: naming is the caller's job, crashing is never an
 * option. Every read returns null on any failure and every write degrades to a dropped temp file,
 * so storage trouble always degrades to a cache miss instead of a crash.
 */
class ThumbFiles(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun writeAtomically(name: String, bytes: ByteArray) {
        // Bytes land on a temp file first and rename into place, so a crash never leaves half a thumb.
        val tmp = File(dir, name + TMP_SUFFIX)
        runCatching {
            tmp.writeBytes(bytes)
            renameAtomically(tmp, file(name))
        }.getOrElse { tmp.delete() }
    }

    fun readBytes(name: String): ByteArray? =
        runCatching { file(name).readBytes() }.getOrNull()

    fun readText(name: String): String? =
        runCatching { file(name).readText() }.getOrNull()

    fun sizeOf(name: String): Long? {
        val entry = file(name)
        if (!entry.isFile) return null
        return entry.length()
    }

    fun delete(name: String) {
        file(name).delete()
    }

    fun thumbEntries(): Map<String, Long> {
        val files = dir.listFiles() ?: return emptyMap()
        return files
            .filter { it.isFile && it.name.endsWith(ThumbKeys.FILE_SUFFIX) }
            .associate { it.name to it.length() }
    }

    fun wipeManaged() {
        val files = dir.listFiles() ?: return
        for (entry in files) {
            if (entry.isFile && isManaged(entry.name)) entry.delete()
        }
    }

    private fun file(name: String): File = File(dir, name)

    private fun isManaged(name: String): Boolean =
        name == ThumbJournal.JOURNAL_FILE || name.endsWith(ThumbKeys.FILE_SUFFIX) || name.endsWith(TMP_SUFFIX)

    private fun renameAtomically(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        target.delete()
        tmp.renameTo(target)
    }

    companion object {
        private const val TMP_SUFFIX = ".tmp"
    }
}
