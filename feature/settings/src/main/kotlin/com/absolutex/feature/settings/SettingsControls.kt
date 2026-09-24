package com.absolutex.feature.settings

import com.absolutex.core.ui.rememberHaptics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.absolutex.core.ui.A11y
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.RotationLock
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.PageLayout
import com.absolutex.model.PageTransition
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import kotlin.math.roundToInt

// Every row meets the §7 accessibility floor: at least 48dp tall, and merges into one
// TalkBack stop per row. The title Text supplies the announced label (never overridden by
// contentDescription, or every row with the same descriptionRes would read identically);
// a helpful descriptionRes rides along as stateDescription instead, alongside the switch's
// own on/off state.

@Composable
fun SwitchSettingRow(
    titleRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    descriptionRes: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(descriptionRes)
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch) {
                haptics.select()
                onChange(it)
            }
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // onCheckedChange is null: the row owns the toggle so TalkBack lands on one node.
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
fun <T> SegmentedSettingRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelRes: (T) -> Int,
    descriptionRes: Int,
    modifier: Modifier = Modifier,
) {
    // The buttons' own labels are the announcement; the group description sits on the row.
    val groupDescription = stringResource(descriptionRes)
    val haptics = rememberHaptics()
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            groupDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = groupDescription },
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = {
                        if (option != selected) haptics.select()
                        onSelect(option)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(stringResource(labelRes(option)), maxLines = 1) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                )
            }
        }
    }
}

@Composable
fun RadioSettingRow(
    titleRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton) {
                if (!selected) haptics.select()
                onClick()
            }
            // No contentDescription override: the merged title Text is the label, so every
            // fit-mode row announces its own name instead of the shared group description.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
fun CacheSizeRow(
    valueMiB: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tracks the thumb during a drag so the store sees one write per gesture, not one per
    // frame; onValueChangeFinished commits it. Keyed on valueMiB so an external change (e.g.
    // restoring a saved value) still overrides an unmoved thumb.
    var pending by remember(valueMiB) { mutableIntStateOf(valueMiB) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_cache_value, pending),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val sliderDescription = stringResource(R.string.settings_cache_size_desc, pending)
        Slider(
            value = pending.toFloat(),
            onValueChange = { pending = it.roundToInt() },
            onValueChangeFinished = { onChange(pending) },
            valueRange = AppPrefs.MIN_CACHE_MIB.toFloat()..AppPrefs.MAX_CACHE_MIB.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics {
                    contentDescription = sliderDescription
                },
        )
    }
}

@Composable
fun AboutRow(modifier: Modifier = Modifier) {
    // Read-only by design: version comes from the package, not from any prefs field, so this
    // row writes nothing (and the contract has no field for it to write to).
    val context = LocalContext.current
    val packageManager = context.packageManager
    val info = packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
    )
    ListItem(
        headlineContent = {
            Text(packageManager.getApplicationLabel(context.applicationInfo).toString())
        },
        supportingContent = { Text(info.versionName ?: "") },
        leadingContent = { RowIcon(Icons.Outlined.Info) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

/**
 * Adds a storage location — the folders the library scans (§5.1).
 *
 * This row exists because the only other way in was the library's empty state, which renders
 * exclusively when there are **zero** locations. After the first folder was added that affordance
 * was gone for good: no second folder could ever be added, and a location pointing somewhere
 * that no longer holds books left the library permanently empty with nothing to do about it.
 *
 * Adding is all this offers. Removing is not needed: `ShellViewModel.rescanLocations` already
 * drops a location whose grant the system no longer holds, so a revoked folder cleans itself up.
 */
@Composable
fun AddStorageLocationRow(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_add_location_title)) },
        supportingContent = { Text(stringResource(R.string.settings_add_location_desc)) },
        leadingContent = { RowIcon(Icons.Outlined.CreateNewFolder) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = A11y.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onAdd),
    )
}

/**
 * The way in to the remote servers list (§5.3).
 *
 * The transports, the list and the form have all shipped; until this row existed nothing in the
 * app navigated to them, so the whole remote feature was unreachable from the UI.
 *
 * `clickable(role = Role.Button)` rather than a bare click: a row that navigates is a button to
 * a screen reader, and without the role TalkBack announces it as plain text with no hint that it
 * does anything. The 48 dp floor is [A11y.MinTouchTarget] — a ListItem is tall enough by default
 * with supporting text, but the floor is what keeps that true at small font scales.
 */
@Composable
fun RemoteServersRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_remote_title)) },
        supportingContent = { Text(stringResource(R.string.settings_remote_desc)) },
        leadingContent = { RowIcon(Icons.Outlined.Dns) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = A11y.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onOpen),
    )
}

/** Open-source licences screen (§5.1 release blocker): positive attribution statements required. */
@Composable
fun OpenSourceLicencesRow(modifier: Modifier = Modifier) {
    // Positive attribution for libjpeg-turbo (IJG) and FreeType (FTL) — both triggered by
    // binary-only distribution (§5.1). The text is read from the vendored licences file.
    val attribution = stringResource(R.string.licences_attribution)
    ListItem(
        headlineContent = { Text(stringResource(R.string.licences_title)) },
        supportingContent = { Text(attribution) },
        leadingContent = { RowIcon(Icons.Outlined.Description) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

/** A row's leading icon in a tonal circle, so a list of links reads as buttons at a glance. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null) }
}
