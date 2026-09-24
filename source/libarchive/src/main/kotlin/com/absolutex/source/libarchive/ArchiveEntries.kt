package com.absolutex.source.libarchive

import android.os.ParcelFileDescriptor

/**
 * An archive's raw entries, for a format that parses the container itself — an EPUB is a ZIP whose
 * pages are named by its own package document, not found by filtering entry names the way
 * [LibArchiveSource] does.
 *
 * Reads go by ordinal, never by name through libarchive: two entries can share a name, and names
 * do not survive the JNI round trip byte for byte (see nativeList). The first entry carrying a
 * name is the one it resolves to, which is what a package document that names each entry once
 * expects. Every [read] takes a fresh descriptor, for the same reason LibArchiveSource does: a
 * shared SAF descriptor corrupts reads made in parallel.
 */
class ArchiveEntries private constructor(
    val names: List<String>,
    private val ordinalOf: Map<String, Int>,
    private val openFd: () -> ParcelFileDescriptor,
) {
    /** One entry's bytes, or null when no entry has [name] or it cannot be read. */
    fun read(name: String): ByteArray? {
        val ordinal = ordinalOf[name] ?: return null
        return openFd().use { LibArchive.nativeExtract(it.fd, ordinal, null) }
    }

    companion object {
        /** Lists [openFd]'s entries, or null when libarchive cannot read it as an archive at all. */
        fun list(openFd: () -> ParcelFileDescriptor): ArchiveEntries? {
            val raw = openFd().use { LibArchive.nativeList(it.fd, BooleanArray(1), BooleanArray(1), null) }
                ?: return null
            val names = raw.map { String(it, Charsets.UTF_8) }
            val ordinalOf = HashMap<String, Int>(names.size)
            names.forEachIndexed { i, name -> ordinalOf.putIfAbsent(name, i) }
            return ArchiveEntries(names, ordinalOf, openFd)
        }
    }
}
