package com.absolutex.feature.library

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
 * A file size, in the reader's language.
 *
 * Delegates to the platform formatter rather than naming the units here. "KB"/"MB" are not the
 * same everywhere, the decimal separator certainly is not, and the platform already matches what
 * the device shows in Settings — so the localisation is better than anything this module would
 * hand-roll, and it tracks the system rather than drifting from it.
 *
 * **This changes displayed sizes.** `Formatter.formatFileSize` has measured in SI units since
 * Android 8, so 1024 bytes now reads "1.02 kB" where the old binary formatter said "1.0 KB".
 * That is the platform's convention and the same one Settings uses; it is a deliberate
 * consequence of the switch, not a rounding bug.
 *
 * Takes a [Context] rather than being composable so the one rule that is ours — a size that
 * could not be read is not rendered as a number — stays testable off-device.
 */
internal fun formatSize(context: Context, bytes: Long): String =
    // sizeBytes comes off disk; a stat failure must never print "-1 B".
    if (bytes < 0) context.getString(R.string.library_size_unknown)
    else Formatter.formatFileSize(context, bytes)

/** [formatSize] for a row, shaped like its neighbours [pagesLabel] and [positionLabel]. */
@Composable
internal fun LibraryBookUi.sizeLabel(): String = formatSize(LocalContext.current, sizeBytes)

/**
 * The single sentence TalkBack reads for a row.
 *
 * Built here rather than left to the default traversal because a row is five or six separate
 * text nodes plus a progress bar: unlabelled, TalkBack reads them one swipe at a time and the
 * selected state never gets announced at all.
 */
@Composable
internal fun LibraryBookUi.accessibilityLabel(isSelected: Boolean, selectionActive: Boolean): String {
    val parts = mutableListOf(displayName, pagesLabel(), positionLabel(), sizeLabel())
    if (selectionActive) {
        parts += if (isSelected) {
            stringResource(R.string.library_row_selected)
        } else {
            stringResource(R.string.library_row_not_selected)
        }
    }
    return parts.joinToString(separator = ", ")
}
