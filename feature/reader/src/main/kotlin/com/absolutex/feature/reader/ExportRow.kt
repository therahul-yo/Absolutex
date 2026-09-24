package com.absolutex.feature.reader

import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.Icons
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * Export the page (§5.2), as one icon button that reports its own result: a download arrow, then a
 * check once the page is written — tap it again to open the file — or a warning if it failed.
 *
 * The result is kept per page: exporting page 3 and turning to page 4 must not leave page 3's
 * "saved" mark pointing at a file the reader is no longer looking at.
 */
@Composable
internal fun ExportButton(page: Int, onExport: suspend () -> Uri?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exported by remember(page) { mutableStateOf<Uri?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var running by remember(page) { mutableStateOf(false) }
    val done = exported
    val (icon, label) = when {
        done != null -> Icons.Outlined.CheckCircle to
            "${stringResource(R.string.reader_export_saved)}. ${stringResource(R.string.reader_export_open)}"
        failed -> Icons.Outlined.ErrorOutline to stringResource(R.string.reader_export_failed)
        else -> Icons.Outlined.Download to stringResource(R.string.reader_export)
    }
    ChromeButton(icon, label, enabled = !running, onClick = {
        if (done != null) {
            context.startActivity(viewIntent(done))
            return@ChromeButton
        }
        running = true
        scope.launch {
            val uri = onExport()
            exported = uri
            failed = uri == null
            running = false
        }
    })
}

/** Read permission travels with the Uri, so the gallery can open a file this app just wrote. */
private fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, "image/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
