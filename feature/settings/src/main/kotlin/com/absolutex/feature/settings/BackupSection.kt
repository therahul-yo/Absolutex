package com.absolutex.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BackupSection(vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri -> if (uri != null) vm.export(uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> if (uri != null) vm.import(uri)
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.backup_description), style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(enabled = !state.busy, onClick = { export.launch("absolutex-backup.json") }) {
                Icon(Icons.Outlined.FileUpload, contentDescription = null)
                Text(stringResource(R.string.backup_export), Modifier.padding(start = 8.dp))
            }
            TextButton(enabled = !state.busy, onClick = { import.launch(arrayOf("application/json", "text/plain")) }) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Text(stringResource(R.string.backup_import), Modifier.padding(start = 8.dp))
            }
        }
        if (state.busy) LinearProgressIndicator()
        state.message?.let { Text(stringResource(it, state.books), style = MaterialTheme.typography.bodyMedium) }
    }
}
