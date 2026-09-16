package com.absolutex.model

/** One entry of a book's contents (§5.2), pointing at the page it starts on. */
data class TocEntry(val title: String, val pageIndex: Int, val depth: Int = 0)

/**
 * A comic archive's contents, taken from the folders its pages live in.
 *
 * Collections and omnibus scans keep one folder per issue ("Absolute Batman 001/001.jpg"), which is
 * the only structure a CBZ carries, so it is the only table of contents there is to build. A flat
 * archive has no contents: one entry called "/" is noise, so nothing is returned and the reader
 * shows no contents button at all.
 */
object Toc {

    fun fromEntryNames(entryNames: List<String>): List<TocEntry> {
        val entries = ArrayList<TocEntry>()
        var previous: String? = null
        entryNames.forEachIndexed { index, name ->
            val folder = name.substringBeforeLast('/', missingDelimiterValue = "")
            if (folder != previous) {
                previous = folder
                if (folder.isNotEmpty()) {
                    entries += TocEntry(
                        title = folder.substringAfterLast('/'),
                        pageIndex = index,
                        depth = folder.count { it == '/' },
                    )
                }
            }
        }
        // A single folder is the archive's own wrapper directory, not a table of contents.
        return if (entries.size > 1) entries else emptyList()
    }
}
