package com.absolutex.feature.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * "Export page" and, once a page is written, where it went plus an action to open it (§5.2).
 *
 * The result is kept per page: exporting page 3 and turning to page 4 must not leave page 3's
 * "saved" line pointing at a file the reader is no longer looking at.
 */
@Composable
internal fun ExportRow(page: Int, onExport: suspend () -> Uri?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exported by remember(page) { mutableStateOf<Uri?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var running by remember(page) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier) {
        TextButton(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    val uri = onExport()
                    exported = uri
                    failed = uri == null
                    running = false
                }
            },
        ) { Text(stringResource(R.string.reader_export)) }
        when {
            exported != null -> {
                Text(
                    stringResource(R.string.reader_export_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { exported?.let { context.startActivity(viewIntent(it)) } }) {
                    Text(stringResource(R.string.reader_export_open))
                }
            }
            failed -> Text(
                stringResource(R.string.reader_export_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Read permission travels with the Uri, so the gallery can open a file this app just wrote. */
private fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, "image/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
