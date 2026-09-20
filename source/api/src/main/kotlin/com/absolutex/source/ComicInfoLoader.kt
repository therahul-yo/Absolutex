package com.absolutex.source

import com.absolutex.model.ComicInfo
import com.absolutex.model.ComicInfoPage

/**
 * Finds the ComicInfo.xml entry inside a container's raw entry list and parses it (§2).
 *
 * Kept in :source:api rather than inside :source:libarchive so the locating and parsing rules
 * are JVM-testable; only the byte extraction stays JNI. Everything here inherits
 * [ComicInfoParser]'s contract: malformed input costs the metadata, never the book.
 */
object ComicInfoLoader {

    /**
     * @param rawNames every entry name in raw archive order, so index == archive ordinal —
     *   including the entries [EntryFilter.isPage] would reject, because ComicInfo.xml is one
     *   of them.
     * @param extract asks for one entry's bytes by raw ordinal and may return null (unreadable).
     * @return the parsed [ComicInfo], or null when the archive carries none, the entry cannot be
     *   read, or the XML is malformed — the same "degrade, never crash" rule as the parser.
     */
    inline fun from(rawNames: List<String>, extract: (ordinal: Int) -> Pair<Int, ByteArray>?): ComicInfo? {
        val ordinal = rawNames.indexOfFirst { isComicInfoName(it) }
        if (ordinal < 0) return null
        val bytes = extract(ordinal)?.second ?: return null
        return ComicInfoParser.parse(bytes.inputStream())
    }

    /**
     * ComicRack writes the file at the archive root, but tools that re-zip a scan nest it; names
     * are compared case-insensitively on the last segment because every writer we have seen keeps
     * the basename.
     */
    fun isComicInfoName(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.equals("ComicInfo.xml", ignoreCase = true)
    }

    /**
     * Re-attaches parsed page metadata to the filtered page list by image index, which is what the
     * reader's spread layout needs before decode (§4). Pages the XML does not describe keep their
     * defaults; an image index that points nowhere is dropped rather than misapplied.
     */
    fun ComicInfo.pageMetadataFor(pageIndicesToEntryNames: List<String>): List<ComicInfoPage> =
        pageIndicesToEntryNames.mapIndexed { index, _ -> pagesByImage[index] ?: ComicInfoPage(index) }
}
