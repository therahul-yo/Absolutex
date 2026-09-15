package com.absolutex.core.scan

/**
 * Book-level library events: the library updates on add/delete/move without a manual
 * rescan (§5.1). Raw paths are classified with the scanner's own predicates
 * ([LibraryScanner.CONTAINER_EXTENSIONS], [LibraryScanner.MIN_IMAGES_FOR_FOLDER_BOOK],
 * `EntryFilter`), so every producer agrees on what a book is.
 *
 * Shared by the filesystem watcher (WatchService over configured roots) and any later
 * provider-side observer: both emit this type, so the repository consumes one event stream and
 * no rival mapping can drift.
 */
sealed interface LibraryChange {
    data class Added(val path: String) : LibraryChange
    data class Removed(val path: String) : LibraryChange
    data class Modified(val path: String) : LibraryChange

    /** A folder crossed the scanner's image-folder rule and became a book. */
    data class FolderPromoted(val path: String, val imageCount: Int) : LibraryChange

    /** The platform dropped events; the consumer must re-walk instead of trusting the delta. */
    data object RescanRequested : LibraryChange
}
