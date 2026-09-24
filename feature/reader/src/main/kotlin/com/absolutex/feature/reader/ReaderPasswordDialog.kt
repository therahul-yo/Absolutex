package com.absolutex.feature.reader

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * The prompt's call site, split out of [ReaderScreen] so that function stays within detekt's
 * 60-line LongMethod limit. No behavioural change: the password still travels back through
 * `open` as an argument and is never kept in the UI state (see [PdfPasswordDialog]).
 */
@Composable
internal fun PasswordPrompt(
    required: Boolean,
    incorrect: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (required) {
        PdfPasswordDialog(incorrect = incorrect, onSubmit = onSubmit, onDismiss = onDismiss)
    }
}

/**
 * The encrypted-PDF prompt: the one open failure the reader can recover from in place.
 *
 * Shaped like the delete confirmation in `:feature:remote` (an `AlertDialog` with resource
 * strings and nothing else), plus the one thing that case has no use for: a password field.
 * The typed password lives only in this composition — `rememberSaveable` survives a rotation
 * while the prompt is up, and leaving the composition drops it. It is handed to [onSubmit]
 * and never written to the ViewModel's state, so a password cannot leak into a saved
 * `ReaderUiState`, a log line, or process death restoration.
 */
@Composable
internal fun PdfPasswordDialog(
    incorrect: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.reader_password_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (incorrect) {
                                R.string.reader_password_body_incorrect
                            } else {
                                R.string.reader_password_body
                            },
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.reader_password_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_password_cancel)) }
        },
    )
}
