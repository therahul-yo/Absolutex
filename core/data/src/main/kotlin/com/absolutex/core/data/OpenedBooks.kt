package com.absolutex.core.data

import com.absolutex.core.scan.LibraryScanner
import com.absolutex.model.BookIdentity
import com.absolutex.source.EntryFilter
import com.absolutex.source.FilenameParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a single file the reader picked into the library, so it can appear in Recent.
 *
 * The library shelves are rows of `library_book`, and only a folder scan wrote those: a file opened
 * on its own left a reading position behind and no row to hang it on, so it never showed anywhere.
 * The row's `contentKey` is the same name-and-size identity the reader saves progress under.
 *
 * Nothing sweeps such a row away: a scan's stale-row delete is scoped to its own root, and a picked
 * document's Uri is not beneath any granted tree.
 */
@Singleton
class OpenedBooks @Inject constructor(private val dao: LibraryDao) {

    /**
     * Records the file at [path]; returns whether a row was written. False for a name that is not
     * a book type the library lists, which the reader may still open by sniffing its content.
     */
    suspend fun record(path: String, fileName: String, sizeBytes: Long?): Boolean {
        val row = openedBookRow(path, fileName, sizeBytes, now = System.currentTimeMillis()) ?: return false
        dao.upsertPreservingAddedAt(listOf(row))
        return true
    }
}

/** The row for one opened file, or null when [fileName] is not a type the library lists. */
internal fun openedBookRow(path: String, fileName: String, sizeBytes: Long?, now: Long): LibraryBook? {
    val extension = EntryFilter.extensionOf(fileName)
    if (extension !in LibraryScanner.CONTAINER_EXTENSIONS) return null
    val parsed = FilenameParser.parse(fileName)
    return LibraryBook(
        path = path,
        // SAF has no File mtime: filename + provider-reported size is the existing book
        // identity; an unknown size falls back to the Uri. Matching identity keeps learned counts.
        contentKey = BookIdentity.ofOrFallback(fileName, sizeBytes, path),
        series = parsed.series,
        title = parsed.title,
        issue = parsed.issue?.value,
        issueRaw = parsed.issue?.raw,
        volume = parsed.volume,
        year = parsed.year,
        sizeBytes = sizeBytes?.coerceAtLeast(0) ?: 0,
        lastModified = now,
        isImageFolder = false,
        pageCount = null,
        addedAt = now,
        seenAtScan = now,
        format = extension,
        fileName = fileName,
    )
}
