package com.absolutex.core.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Per-app language picker (§6, milestone 3).
 *
 * Self-contained on purpose: it reads and writes the system's own per-app language, so the
 * screen hosting it only has to place it. minSdk 33 means [LocaleManager] is the platform API
 * and no AppCompat shim is involved.
 *
 * **Nothing is stored in DataStore.** The selection lives in the system's per-app language
 * setting, which survives reinstall-independently of our preferences and is also reachable from
 * Settings > Apps > Absolutex > Language. A preference key here would be a second source of
 * truth for the same fact — and, once shipped, a key we could never rename.
 *
 * Applying a language recreates the activity, which is how the new resources take effect. The
 * selected row is held locally so the radio responds immediately; the recreate re-reads the
 * real value, so the two cannot drift.
 *
 * @param tags the languages to offer, defaulting to [SUPPORTED_LOCALES].
 */
@Composable
fun LanguagePicker(
    modifier: Modifier = Modifier,
    tags: List<String> = SUPPORTED_LOCALES,
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val options = languageOptions(tags, stringResource(R.string.language_system_default))
    var selected by remember { mutableStateOf(selectedTag(manager.applicationLocales.tags(), options)) }

    Column(modifier = modifier.selectableGroup()) {
        Text(
            text = stringResource(R.string.language_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        options.forEach { option ->
            LanguageRow(
                label = option.label,
                selected = option.tag == selected,
                onSelect = {
                    selected = option.tag
                    manager.applicationLocales = option.tag
                        ?.let { LocaleList.forLanguageTags(it) }
                        ?: LocaleList.getEmptyLocaleList()
                },
            )
        }
    }
}

/**
 * One language row.
 *
 * The click and its semantics sit on the row rather than the [RadioButton], so the whole row is
 * the target and TalkBack announces one node — label, radio role and selected state together —
 * instead of a button and a stray piece of text. The button takes a null callback for the same
 * reason, mirroring the settings surface's switch rows.
 */
@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = A11y.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The tags in a [LocaleList], in order; empty when the app follows the system. */
private fun LocaleList.tags(): List<String> = List(size()) { get(it).toLanguageTag() }
