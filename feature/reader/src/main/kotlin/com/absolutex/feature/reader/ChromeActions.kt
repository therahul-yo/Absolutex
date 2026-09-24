package com.absolutex.feature.reader

import com.absolutex.core.ui.A11y
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = indicator,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = indicatorDescription },
        )
        if (hasContents) {
            ChromeButton(Icons.AutoMirrored.Outlined.List, stringResource(R.string.reader_contents), onContents)
        }
        ChromeButton(Icons.Outlined.Tune, stringResource(R.string.reader_options), onOptions)
        ExportButton(page, onExport)
    }
}

/** A chrome action: a full-size tonal icon button, labelled for TalkBack. */
@Composable
internal fun ChromeButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(A11y.MinTouchTarget)) {
        Icon(icon, contentDescription = label)
    }
}
