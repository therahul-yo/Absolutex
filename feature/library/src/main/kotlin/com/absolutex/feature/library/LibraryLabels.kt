package com.absolutex.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** "20 pages", or an honest "Page count unknown" for a container nobody has opened yet. */
@Composable
internal fun LibraryBookUi.pagesLabel(): String {
    val pages = pageCount
    return if (pages == null || pages <= 0) {
        stringResource(R.string.library_book_pages_unknown)
    } else {
        pluralStringResource(R.plurals.library_book_pages, pages, pages)
    }
}

/** Where the reader left off, phrased for the read state rather than as a bare number. */
@Composable
internal fun LibraryBookUi.positionLabel(): String = when (readState) {
    ReadState.UNREAD -> stringResource(R.string.library_book_unread)
    ReadState.FINISHED -> stringResource(R.string.library_book_finished)
    ReadState.IN_PROGRESS -> {
        val page = (currentPage ?: 0) + 1
        val total = pageCount
        if (total == null || total <= 0) {
            stringResource(R.string.library_book_unread)
        } else {
            stringResource(R.string.library_book_on_page, page, total)
        }
    }
}

/**
 * The single sentence TalkBack reads for a row.
 *
 * Built here rather than left to the default traversal because a row is five or six separate
 * text nodes plus a progress bar: unlabelled, TalkBack reads them one swipe at a time and the
 * selected state never gets announced at all.
 */
@Composable
internal fun LibraryBookUi.accessibilityLabel(isSelected: Boolean, selectionActive: Boolean): String {
    val parts = mutableListOf(displayName, pagesLabel(), positionLabel(), formatSize(sizeBytes))
    if (selectionActive) {
        parts += if (isSelected) {
            stringResource(R.string.library_row_selected)
        } else {
            stringResource(R.string.library_row_not_selected)
        }
    }
    return parts.joinToString(separator = ", ")
}
