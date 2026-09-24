package com.absolutex.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.absolutex.core.ui.A11y

/**
 * One scanned folder, with a remove button. Removing asks first: it takes the folder's books out
 * of the library (the files themselves are untouched, which the confirmation says).
 */
@Composable
internal fun LocationRow(location: StorageLocation, onRemove: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(location.label) },
        // The confirmation is inline — the row itself asks — rather than a dialog: a dialog pulls
        // in a component the APK's size ceiling has no room for.
        supportingContent = if (confirming) {
            { Text(stringResource(R.string.settings_location_remove_body)) }
        } else {
            null
        },
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        trailingContent = {
            if (confirming) {
                Row {
                    TextButton(onClick = { confirming = false }) {
                        Text(stringResource(R.string.settings_location_remove_cancel))
                    }
                    TextButton(onClick = {
                        confirming = false
                        onRemove(location.uri)
                    }) { Text(stringResource(R.string.settings_location_remove_confirm)) }
                }
            } else {
                IconButton(onClick = { confirming = true }, modifier = Modifier.size(A11y.MinTouchTarget)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.settings_location_remove, location.label),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
