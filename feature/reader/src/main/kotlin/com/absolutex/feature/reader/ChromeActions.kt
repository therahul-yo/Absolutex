package com.absolutex.feature.reader

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The chrome's action row: where you are, and the things a reader reaches for without leaving the
 * page. It wraps, because a phone held upright fits three of these and a tablet fits all of them.
 *
 * Options (fit, flow, layout) live behind a toggle rather than on screen: in landscape the chrome
 * had grown taller than the screen and overlapped its own top bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChromeActions(
    indicator: String,
    indicatorDescription: String,
    hasContents: Boolean,
    onContents: () -> Unit,
    onOptions: () -> Unit,
    page: Int,
    onExport: suspend () -> Uri?,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = indicator,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .semantics { contentDescription = indicatorDescription },
        )
        if (hasContents) {
            TextButton(onClick = onContents) { Text(stringResource(R.string.reader_contents)) }
        }
        TextButton(onClick = onOptions) { Text(stringResource(R.string.reader_options)) }
        ExportRow(page, onExport)
    }
}
