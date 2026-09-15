package com.absolutex.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

/** Shelf title and count, rendered as an item of the one lazy container. */
@Composable
internal fun ShelfHeader(shelf: Shelf) {
    Column(Modifier.padding(horizontal = Space.Edge, vertical = Space.Row)) {
        Text(shelf.name, style = MaterialTheme.typography.titleMedium)
        Text(
            pluralStringResource(R.plurals.library_shelf_count, shelf.size, shelf.size),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * What the screen shows instead of a list.
 *
 * §5.1 is explicit that a fresh install must say what to do next, so the no-locations case is the
 * only one with an action: everything else is a statement of fact, and offering a button that
 * does not address the problem is worse than offering none.
 */
@Composable
internal fun LibraryEmptyState(
    reason: LibraryEmptyReason,
    section: HomeSection,
    query: String,
    onAddLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reason == LibraryEmptyReason.NONE) return
    Column(
        modifier = modifier.fillMaxSize().padding(Space.Edge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.Row, Alignment.CenterVertically),
    ) {
        Text(
            emptyTitle(reason),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            emptyBody(reason, section, query),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (reason == LibraryEmptyReason.NO_LOCATIONS) {
            Button(
                onClick = onAddLocation,
                modifier = Modifier.heightIn(min = Space.MinTouchTarget),
            ) { Text(stringResource(R.string.library_empty_no_locations_action)) }
        }
    }
}

@Composable
private fun emptyTitle(reason: LibraryEmptyReason): String = when (reason) {
    LibraryEmptyReason.NO_LOCATIONS -> stringResource(R.string.library_empty_no_locations_title)
    LibraryEmptyReason.LIBRARY_EMPTY -> stringResource(R.string.library_empty_library_title)
    LibraryEmptyReason.NO_SEARCH_MATCHES -> stringResource(R.string.library_empty_search_title)
    LibraryEmptyReason.SECTION_EMPTY, LibraryEmptyReason.NONE ->
        stringResource(R.string.library_empty_section_title)
}

@Composable
private fun emptyBody(reason: LibraryEmptyReason, section: HomeSection, query: String): String =
    when (reason) {
        LibraryEmptyReason.NO_LOCATIONS -> stringResource(R.string.library_empty_no_locations_body)
        LibraryEmptyReason.LIBRARY_EMPTY -> stringResource(R.string.library_empty_library_body)
        LibraryEmptyReason.NO_SEARCH_MATCHES -> stringResource(R.string.library_empty_search_body, query)
        LibraryEmptyReason.SECTION_EMPTY, LibraryEmptyReason.NONE -> sectionEmptyBody(section)
    }

@Composable
private fun sectionEmptyBody(section: HomeSection): String = when (section) {
    HomeSection.READING -> stringResource(R.string.library_empty_reading_body)
    HomeSection.UNREAD -> stringResource(R.string.library_empty_unread_body)
    HomeSection.FAVORITES -> stringResource(R.string.library_empty_favorites_body)
    HomeSection.SERIES, HomeSection.FOLDERS -> stringResource(R.string.library_empty_library_body)
}

@Composable
internal fun HomeSection.label(): String = when (this) {
    HomeSection.READING -> stringResource(R.string.library_section_reading)
    HomeSection.SERIES -> stringResource(R.string.library_section_series)
    HomeSection.FOLDERS -> stringResource(R.string.library_section_folders)
    HomeSection.UNREAD -> stringResource(R.string.library_section_unread)
    HomeSection.FAVORITES -> stringResource(R.string.library_section_favorites)
}

/**
 * Stand-in for a navigation icon.
 *
 * `NavigationSuiteScope.item` requires an icon slot, and the Material icon artifacts are not on
 * this module's classpath. A short glyph keeps the bar readable and the labels do the real work
 * for TalkBack.
 * TODO(library): swap for real icons once material-icons is added to the catalog.
 */
@Composable
internal fun HomeSection.glyph(): String = when (this) {
    HomeSection.READING -> "▶"
    HomeSection.SERIES -> "◆"
    HomeSection.FOLDERS -> "▣"
    HomeSection.UNREAD -> "○"
    HomeSection.FAVORITES -> "★"
}
