package com.absolutex.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.remote.sync.ConnectionResult
import com.absolutex.remote.sync.RemoteKind

private const val SMB_PORT_DEFAULT = "445"
private const val FTP_PORT_DEFAULT = "21"

/**
 * Add-and-edit form: kind picker, per-kind fields with inline errors, a live connection
 * test, and save. SMB shows a pending note instead of a test button — its transport has
 * no in-process server and has not merged yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFormScreen(
    viewModel: ServerFormViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    if (status.saved) {
        LaunchedEffect(Unit) { onDone() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.remote_add_server)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KindPicker(kind = form.kind, onKind = { viewModel.update(form.copy(kind = it)) })
            FormFields(form = form, status = status, onUpdate = viewModel::update)
            if (form.kind == RemoteKind.SMB) {
                Text(stringResource(R.string.remote_test_pending_smb))
            } else {
                TestButton(testing = status.testing, onTest = viewModel::testConnection)
            }
            status.testResult?.let { TestResultLine(result = it) }
            if (status.saveBlocked) {
                Text(stringResource(R.string.remote_invalid_details))
            }
            val saveLabel = stringResource(R.string.remote_save)
            Button(
                onClick = viewModel::save,
                modifier = Modifier.semantics { contentDescription = saveLabel },
            ) {
                Text(saveLabel)
            }
        }
    }
}

@Composable
private fun FormFields(
    form: ServerForm,
    status: ServerFormStatus,
    onUpdate: (ServerForm) -> Unit,
) {
    when (form.kind) {
        RemoteKind.SMB -> SmbFields(form, status, onUpdate)
        RemoteKind.FTP -> FtpFields(form, status, onUpdate)
        RemoteKind.KOMGA, RemoteKind.KAVITA -> SyncFields(form, status, onUpdate)
    }
}

@Composable
private fun SmbFields(
    form: ServerForm,
    status: ServerFormStatus,
    onUpdate: (ServerForm) -> Unit,
) {
    FormField(R.string.remote_field_host, form.host, ServerFormViewModel.FIELD_HOST, status) {
        onUpdate(form.copy(host = it))
    }
    FormField(R.string.remote_field_share, form.share, ServerFormViewModel.FIELD_SHARE, status) {
        onUpdate(form.copy(share = it))
    }
    FormField(R.string.remote_field_path, form.path, ServerFormViewModel.FIELD_PATH, status) {
        onUpdate(form.copy(path = it))
    }
    FormField(
        R.string.remote_field_port, form.port, ServerFormViewModel.FIELD_PORT, status,
        placeholder = SMB_PORT_DEFAULT, keyboardType = KeyboardType.Number,
    ) {
        onUpdate(form.copy(port = it))
    }
    FormField(R.string.remote_field_username, form.username, ServerFormViewModel.FIELD_USERNAME, status) {
        onUpdate(form.copy(username = it))
    }
    FormField(
        R.string.remote_field_password, form.password, ServerFormViewModel.FIELD_PASSWORD, status,
        secret = true,
    ) {
        onUpdate(form.copy(password = it))
    }
    CheckRow(R.string.remote_allow_unsigned, form.allowUnsigned) {
        onUpdate(form.copy(allowUnsigned = it))
    }
}

@Composable
private fun FtpFields(
    form: ServerForm,
    status: ServerFormStatus,
    onUpdate: (ServerForm) -> Unit,
) {
    FormField(R.string.remote_field_host, form.host, ServerFormViewModel.FIELD_HOST, status) {
        onUpdate(form.copy(host = it))
    }
    FormField(
        R.string.remote_field_port, form.port, ServerFormViewModel.FIELD_PORT, status,
        placeholder = FTP_PORT_DEFAULT, keyboardType = KeyboardType.Number,
    ) {
        onUpdate(form.copy(port = it))
    }
    FormField(R.string.remote_field_path, form.path, ServerFormViewModel.FIELD_PATH, status) {
        onUpdate(form.copy(path = it))
    }
    FormField(R.string.remote_field_username, form.username, ServerFormViewModel.FIELD_USERNAME, status) {
        onUpdate(form.copy(username = it))
    }
    FormField(
        R.string.remote_field_password, form.password, ServerFormViewModel.FIELD_PASSWORD, status,
        secret = true,
    ) {
        onUpdate(form.copy(password = it))
    }
    CheckRow(R.string.remote_use_tls, form.useTls) {
        onUpdate(form.copy(useTls = it))
    }
    CheckRow(R.string.remote_allow_cleartext, form.allowCleartext) {
        onUpdate(form.copy(allowCleartext = it))
    }
}

@Composable
private fun SyncFields(
    form: ServerForm,
    status: ServerFormStatus,
    onUpdate: (ServerForm) -> Unit,
) {
    FormField(
        R.string.remote_field_base_url, form.baseUrl, ServerFormViewModel.FIELD_BASE_URL, status,
        keyboardType = KeyboardType.Uri,
    ) {
        onUpdate(form.copy(baseUrl = it))
    }
    CheckRow(R.string.remote_use_api_key, form.useApiKey) {
        onUpdate(form.copy(useApiKey = it))
    }
    if (form.useApiKey) {
        FormField(
            R.string.remote_field_api_key, form.apiKey, ServerFormViewModel.FIELD_API_KEY, status,
            secret = true,
        ) {
            onUpdate(form.copy(apiKey = it))
        }
    } else {
        FormField(R.string.remote_field_username, form.username, ServerFormViewModel.FIELD_USERNAME, status) {
            onUpdate(form.copy(username = it))
        }
        FormField(
            R.string.remote_field_password, form.password, ServerFormViewModel.FIELD_PASSWORD, status,
            secret = true,
        ) {
            onUpdate(form.copy(password = it))
        }
    }
    CheckRow(R.string.remote_allow_cleartext, form.allowCleartext) {
        onUpdate(form.copy(allowCleartext = it))
    }
}

@Composable
private fun KindPicker(kind: RemoteKind, onKind: (RemoteKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KindChip(RemoteKind.SMB, R.string.remote_kind_smb, kind, onKind)
        KindChip(RemoteKind.FTP, R.string.remote_kind_ftp, kind, onKind)
        KindChip(RemoteKind.KOMGA, R.string.remote_kind_komga, kind, onKind)
        KindChip(RemoteKind.KAVITA, R.string.remote_kind_kavita, kind, onKind)
    }
}

@Composable
private fun KindChip(
    value: RemoteKind,
    labelRes: Int,
    selected: RemoteKind,
    onKind: (RemoteKind) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onKind(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun FormField(
    labelRes: Int,
    value: String,
    field: String,
    status: ServerFormStatus,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onValue: (String) -> Unit,
) {
    val invalid = status.invalidFields.contains(field)
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(labelRes)) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.remote_invalid_field)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(labelRes: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val label = stringResource(labelRes)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onChecked,
        ),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun TestButton(testing: Boolean, onTest: () -> Unit) {
    val label = stringResource(R.string.remote_test_connection)
    TextButton(
        onClick = onTest,
        enabled = !testing,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        if (testing) {
            CircularProgressIndicator(
                Modifier.semantics { contentDescription = label },
            )
        } else {
            Text(label)
        }
    }
}

@Composable
private fun TestResultLine(result: ConnectionResult) {
    val text = when (result) {
        ConnectionResult.Ok -> stringResource(R.string.remote_result_ok)
        ConnectionResult.AuthFailed -> stringResource(R.string.remote_result_auth_failed)
        ConnectionResult.Unreachable -> stringResource(R.string.remote_result_unreachable)
        ConnectionResult.SecurityRefused -> stringResource(R.string.remote_result_security_refused)
        ConnectionResult.NotFound -> stringResource(R.string.remote_result_not_found)
    }
    Text(text)
}
