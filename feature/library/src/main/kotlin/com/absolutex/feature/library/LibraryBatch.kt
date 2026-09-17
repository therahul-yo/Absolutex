package com.absolutex.feature.library

/**
 * What the reader has already recorded for one selected book, as [planSetRead] needs it.
 *
 * Both counts, because either can be the only one that knows: the scan knows an image folder's
 * page count before it is opened, and the reader is the only thing that ever learns a container's.
 */
internal data class ReadTarget(
    /** `LibraryBook.contentKey`, which is also `ReadingProgress.bookId`. */
    val bookId: String,
    /** What the scanner wrote. Always null for a container: it cannot be known without opening. */
    val scannedPageCount: Int?,
    /** What the reader stored the last time it opened this book, if it ever did. */
    val storedPageCount: Int?,
)

internal data class ReadWrite(val bookId: String, val pageIndex: Int, val pageCount: Int)

internal data class ReadPlan(val writes: List<ReadWrite>, val skipped: Int)

/**
 * Decides what marking a selection read — or unread — should write.
 *
 * Pure and database-free so the rules are assertable without a device, because this is exactly
 * where the bug was: `setRead` trusted `LibraryBook.pageCount`, which the scanner leaves null for
 * every container, so Mark read on an opened 45-page CBR always answered "needs opening first",
 * and Mark unread then overwrote the 45 the reader had learned with a 0.
 *
 * @param read true marks the selection finished, false clears its position.
 * @return the rows to write, and how many books could not be touched.
 */
internal fun planSetRead(targets: List<ReadTarget>, read: Boolean): ReadPlan {
    val writes = ArrayList<ReadWrite>(targets.size)
    var skipped = 0
    for (target in targets) {
        // The reader's own count is the fallback, exactly as LibraryBookUi already does when it
        // renders a position — one fallback rule in the module, not two that can disagree.
        val pages = target.scannedPageCount?.takeIf { it > 0 }
            ?: target.storedPageCount?.takeIf { it > 0 }
        if (read) {
            // No count anywhere means no honest "last page". Counting it as skipped says so.
            if (pages == null) {
                skipped++
                continue
            }
            writes += ReadWrite(target.bookId, pageIndex = pages - 1, pageCount = pages)
        } else {
            // Page 0 clears the position. The *count* is kept: 45 is what opening the file cost,
            // and overwriting it with 0 throws away the only place that number exists.
            writes += ReadWrite(target.bookId, pageIndex = 0, pageCount = pages ?: 0)
        }
    }
    return ReadPlan(writes, skipped)
}
