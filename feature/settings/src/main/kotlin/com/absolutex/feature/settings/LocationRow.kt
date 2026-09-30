package com.absolutex.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.A11y

/** A folder uses the card's inset directly; ListItem would add its own inset and vertical padding. */
@Composable
internal fun LocationRow(location: StorageLocation, onRemove: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = A11y.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                location.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            IconButton(onClick = { confirming = !confirming }, modifier = Modifier.size(A11y.MinTouchTarget)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_location_remove, location.label),
                )
            }
        }
        if (confirming) {
            Text(
                stringResource(R.string.settings_location_remove_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.settings_location_remove_cancel))
                }
                TextButton(onClick = { confirming = false; onRemove(location.uri) }) {
                    Text(stringResource(R.string.settings_location_remove_confirm))
                }
            }
        }
    }
}

/** Add another library folder, on the same inset and icon grid as saved folders. */
@Composable
fun AddStorageLocationRow(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_add_location_desc)
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onAdd)
            .semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.settings_add_location_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(A11y.MinTouchTarget).padding(12.dp),
        )
    }
}
