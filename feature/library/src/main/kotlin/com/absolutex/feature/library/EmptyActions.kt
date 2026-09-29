package com.absolutex.feature.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The buttons under an empty state: what would actually change it, and nothing else.
 *
 * No folder yet: add one, or open a single file. Folders that yielded nothing, or could not be
 * read: rescan, or add another. Every other state is a statement of fact and gets no button.
 */
@Composable
internal fun EmptyActions(
    reason: LibraryEmptyReason,
    onAddLocation: () -> Unit,
    onOpenFile: () -> Unit,
    onRescan: () -> Unit,
) {
    val addFolder = stringResource(R.string.library_empty_no_locations_action)
    when (reason) {
        LibraryEmptyReason.NO_LOCATIONS -> {
            EmptyButton(addFolder, Icons.Outlined.CreateNewFolder, primary = true, onClick = onAddLocation)
            EmptyButton(
                stringResource(R.string.library_empty_open_file_action),
                Icons.Outlined.FileOpen,
                primary = false,
                onClick = onOpenFile,
            )
        }
        LibraryEmptyReason.LIBRARY_EMPTY, LibraryEmptyReason.SCAN_FAILED -> {
            EmptyButton(
                stringResource(R.string.library_empty_rescan_action),
                Icons.Outlined.Refresh,
                primary = true,
                onClick = onRescan,
            )
            EmptyButton(addFolder, Icons.Outlined.CreateNewFolder, primary = false, onClick = onAddLocation)
        }
        else -> Unit
    }
}

@Composable
private fun EmptyButton(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = BigButtonHeight)
    val padding = ButtonDefaults.ButtonWithIconContentPadding
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
    if (primary) {
        Button(onClick, modifier, contentPadding = padding) { content() }
    } else {
        OutlinedButton(onClick, modifier, contentPadding = padding) { content() }
    }
}

private val BigButtonHeight = 56.dp
