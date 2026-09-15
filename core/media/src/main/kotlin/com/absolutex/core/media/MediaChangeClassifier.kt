package com.absolutex.core.media

import com.absolutex.core.scan.LibraryChange
import com.absolutex.core.scan.LibraryScanner
import com.absolutex.source.EntryFilter
import java.io.File

/** One MediaStore row resolved to a filesystem path; absence means the row is already gone. */
data class MediaRow(val id: Long, val path: String)

/** Bounded folder census behind a promotion decision; queried per parent dir, never unbounded. */
data class FolderStats(val imageCount: Int, val hasVisibleSubdir: Boolean)

/**
 * Pure mapping from MediaStore rows to [LibraryChange]; every branch runs on plain JVM, so
 * this is where the honest coverage lives and the observer shell stays thin.
 *
 * Classification reuses the scanner's own predicates ([LibraryScanner.CONTAINER_EXTENSIONS],
 * [LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK], [EntryFilter], [LibraryScanner.shouldSkip]):
 * duplicating the junk/extension lists here would let scan and observer drift on what a book is.
 */
object MediaChangeClassifier {

    /** A flush maps at most this many row IDs; the overflow degrades to one RescanRequested. */
    const val MAX_IDS_PER_FLUSH = 512

    /** A flush queries at most this many parent dirs; extra dirs degrade to RescanRequested. */
    const val MAX_DIR_QUERIES_PER_FLUSH = 32

    /**
     * Maps one resolved row. Containers are books on sight; loose images return null here and
     * resolve at folder level via [needsFolderEvaluation], because one image is never a book.
     */
    fun classifyRow(path: String, includeHidden: Boolean): LibraryChange? {
        if (isSkippedFilePath(path, includeHidden)) return null
        val file = File(path)
        if (EntryFilter.extensionOf(file.name) in LibraryScanner.CONTAINER_EXTENSIONS) {
            return LibraryChange.Added(path)
        }
        return null
    }

    /** True when this row can only matter through its parent folder's image census. */
    fun needsFolderEvaluation(path: String, includeHidden: Boolean): Boolean {
        if (isSkippedFilePath(path, includeHidden)) return false
        return EntryFilter.isPage(File(path).name)
    }

    /**
     * Folder-level decision from a bounded census. [wasBook] is the observer's remembered
     * state, so the first sighting of an existing book reads as modification, not promotion.
     */
    fun classifyFolder(
        dirPath: String,
        stats: FolderStats,
        wasBook: Boolean,
        includeHidden: Boolean,
    ): LibraryChange? {
        if (isSkippedDirPath(dirPath, includeHidden)) return null
        val isNow = !stats.hasVisibleSubdir &&
            stats.imageCount >= LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK
        return when {
            isNow && !wasBook -> LibraryChange.FolderPromoted(dirPath, stats.imageCount)
            isNow -> LibraryChange.Modified(dirPath)
            !isNow && wasBook -> LibraryChange.Removed(dirPath)
            else -> null
        }
    }

    /**
     * True for our own export dir. Segment match, not prefix: "Pictures/Absolutex2/x.cbz"
     * must not suppress a real library change next to our exports.
     */
    fun isSelfExport(path: String): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val leaf = segments.indexOf("Absolutex")
        return leaf > 0 && segments[leaf - 1] == "Pictures"
    }

    /** Dedupes a burst to distinct IDs, bounded so a bulk import cannot wedge one flush. */
    fun coalesce(ids: Collection<Long>): List<Long> =
        ids.distinct().take(MAX_IDS_PER_FLUSH)

    /** True when [coalesce] dropped IDs the flush can no longer name honestly. */
    fun overflowed(ids: Collection<Long>): Boolean =
        ids.distinct().size > MAX_IDS_PER_FLUSH

    // Flat MediaStore paths get no per-level walk, so every ancestor segment is checked here:
    // the walk would have refused to descend into a junk/hidden dir before ever seeing the file.
    private fun isSkippedFilePath(path: String, includeHidden: Boolean): Boolean {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (dirSegmentsSkipped(segments.dropLast(1), includeHidden)) return true
        return LibraryScanner.shouldSkip(File(path), includeHidden)
    }

    private fun isSkippedDirPath(dirPath: String, includeHidden: Boolean): Boolean {
        val segments = dirPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return dirSegmentsSkipped(segments, includeHidden)
    }

    private fun dirSegmentsSkipped(segments: List<String>, includeHidden: Boolean): Boolean =
        segments.any { (!includeHidden && it.startsWith(".")) || EntryFilter.isJunkDirectory(it) }
}
