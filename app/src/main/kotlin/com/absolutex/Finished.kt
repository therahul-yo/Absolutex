package com.absolutex

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.absolutex.core.data.LibraryBook
import com.absolutex.core.ui.Motion

/** What the reader knows once a book's last page is turned: the book that comes next, if any. */
internal data class Finished(val next: LibraryBook?)

/**
 * The end of a book: a card that rises from the bottom saying it is finished, offering the next
 * issue when the library has one and a way back to the library. Back dismisses it and leaves the
 * reader on the last page.
 */
@Composable
internal fun BoxScope.EndOfBookCard(
    finished: Finished?,
    onNext: (LibraryBook) -> Unit,
    onLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = finished != null, onBack = onDismiss)
    AnimatedVisibility(
        finished != null,
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(12.dp).navigationBarsPadding().fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.TaskAlt, contentDescription = null)
                    Text(stringResource(R.string.end_finished), style = MaterialTheme.typography.headlineSmall)
                }
                val next = finished?.next
                if (next != null) {
                    Text(
                        stringResource(R.string.end_next_is, next.label()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onLibrary, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.end_library))
                    }
                    if (next != null) {
                        Button(onClick = { onNext(next) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.end_next))
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A next book's name as a person would know it: its title, else its series, else its file. */
private fun LibraryBook.label(): String =
    title ?: series ?: fileName.substringBeforeLast('.').ifEmpty { path.substringAfterLast('/') }
