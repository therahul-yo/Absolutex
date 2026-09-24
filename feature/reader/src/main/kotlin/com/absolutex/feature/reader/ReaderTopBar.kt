package com.absolutex.feature.reader

import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The chrome's top bar (§5.2): close, and the title of what is open.
 *
 * Closing goes through the back dispatcher rather than finishing the activity, so it behaves
 * exactly like the system back it mirrors — including predictive back and returning to whatever
 * opened the book.
 */
@Composable
internal fun ReaderTopBar(title: String, onSettings: (() -> Unit)?, modifier: Modifier = Modifier) {
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = CHROME_ALPHA),
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { back?.onBackPressed() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.reader_close))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            onSettings?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.reader_settings))
                }
            }
        }
    }
}
