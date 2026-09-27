package com.absolutex.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** The words under a card's cover. Compact (a narrow column): one line of title and progress. */
@Composable
internal fun CardFooter(book: LibraryBookUi, compact: Boolean) {
    Column(
        Modifier.padding(horizontal = Space.Row, vertical = Space.Gap),
        verticalArrangement = Arrangement.spacedBy(Space.Tight),
    ) {
        Text(
            book.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        // A comic card says how far in you are; a document, how big it is.
        Text(
            (if (book.isBook) null else book.progressLabel()) ?: book.sizeLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (!compact) FormatAndDate(book, Modifier.padding(top = Space.Tight))
    }
}

/** From this many columns a card drops its format and date and keeps one line of title. */
internal const val COMPACT_FROM = 3

/** From this many columns a card is its cover alone. */
internal const val COVERS_ONLY_FROM = 4
