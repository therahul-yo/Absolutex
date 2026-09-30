package com.absolutex.source.libarchive

import java.io.File
import java.io.IOException

/** Where the cache lives and which archive's copy it is: [root] is the app's cache directory. */
class SolidCacheConfig(val root: File, val key: String)

/**
 * The cache's directory tree: one directory per archive, named by [SolidCacheKey], holding one
 * file per entry (named by ordinal, never by entry name) and a completion marker written last.
 *
 * A directory without the marker is a pass that never finished, so it is never trusted: the next
 * open discards it. Eviction is least-recently-used by the marker's modification time, oldest
 * first, with unfinished directories going before any finished one.
 *
 * Every archive is [claim]ed for as long as a source has it open: two sources on the same archive
 * would race to fill one directory, and eviction must not pull pages out from under a reader.
 */
internal class SolidCacheStore(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val base = File(root, BASE_DIR)

    fun dir(key: String): File {
        require(KEY_PATTERN.matches(key)) { "not a cache key" }
        return File(base, key)
    }

    /** True when this caller now owns [key]; false when another open source holds it. */
    fun claim(key: String): Boolean = synchronized(claimed) { claimed.add(dir(key).path) }

    fun release(key: String) {
        synchronized(claimed) { claimed.remove(dir(key).path) }
    }

    /** A finished pass for exactly [entryCount] ordinals is on disk. */
    fun isComplete(key: String, entryCount: Int): Boolean =
        runCatching { File(dir(key), COMPLETE).readText() == marker(entryCount) }.getOrDefault(false)

    /** Writes the marker last, and atomically: a crash cannot leave a half-written one. */
    @Throws(IOException::class)
    fun markComplete(key: String, entryCount: Int) {
        val dir = dir(key)
        val tmp = File(dir, "$COMPLETE.tmp")
        tmp.writeText(marker(entryCount))
        if (!tmp.renameTo(File(dir, COMPLETE))) throw IOException("could not publish the cache marker")
    }

    /** Marks the archive as just used, which is what keeps it from being evicted first. */
    fun touch(key: String) {
        File(dir(key), COMPLETE).setLastModified(clock())
    }

    fun discard(key: String) {
        dir(key).deleteRecursively()
    }

    /** Bytes held by every archive's directory. */
    fun totalBytes(): Long = archives().sumOf(::sizeOf)

    /** Every archive's directory, whatever state it is in. */
    fun archives(): List<File> = base.listFiles { f -> f.isDirectory }?.toList().orEmpty()

    /** The directories a source has open right now, by path. */
    fun claimedPaths(): Set<String> = synchronized(claimed) { claimed.toSet() }

    private fun marker(entryCount: Int) = "$MARKER_VERSION $entryCount"

    companion object {
        private const val BASE_DIR = "solid-archives"

        /** The marker file, written last. */
        const val COMPLETE = "complete"
        private const val MARKER_VERSION = 1
        private val KEY_PATTERN = Regex("[0-9a-f]{16,64}")

        /** Process-wide: sources opened on one archive from anywhere share this. */
        private val claimed = HashSet<String>()
    }
}
