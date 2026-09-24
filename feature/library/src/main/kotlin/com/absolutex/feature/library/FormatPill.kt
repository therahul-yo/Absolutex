package com.absolutex.feature.library

import com.absolutex.core.data.FOLDER_FORMAT
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The format as a solid pill (".CBR", ".PDF") with the date at the far end. The pill is the
 * brightest thing on a card after the cover, so a shelf of mixed formats reads at a glance.
 * A book whose format is not known yet (scanned before the column existed) shows only the date.
 */
@Composable
internal fun FormatAndDate(book: LibraryBookUi, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (book.format.isNotEmpty()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    if (book.format == FOLDER_FORMAT) {
                        stringResource(R.string.library_format_folder)
                    } else {
                        stringResource(R.string.library_format_pill, book.format.uppercase())
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            book.dateLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
