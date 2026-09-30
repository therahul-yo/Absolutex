package com.absolutex.source.libarchive

import java.io.File

/**
 * Finds room for a book of [bookBytes] under [capBytes] by evicting whole archives, least recently
 * used first (an unfinished directory has no marker and goes before any finished one). An archive
 * a source has open is never evicted. Returns whether the book now fits.
 */
internal fun SolidCacheStore.makeRoom(bookBytes: Long, capBytes: Long): Boolean {
    val dirs = archives()
    var total = dirs.sumOf(::sizeOf)
    val held = claimedPaths()
    val queue = dirs.filter { it.path !in held }.sortedBy(::lastUsed).iterator()
    while (total + bookBytes > capBytes && queue.hasNext()) {
        val victim = queue.next()
        total -= sizeOf(victim)
        victim.deleteRecursively()
    }
    return total + bookBytes <= capBytes
}

private fun lastUsed(dir: File): Long =
    File(dir, SolidCacheStore.COMPLETE).takeIf { it.isFile }?.lastModified() ?: Long.MIN_VALUE

/** Bytes in an archive's directory, which is flat. */
internal fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
