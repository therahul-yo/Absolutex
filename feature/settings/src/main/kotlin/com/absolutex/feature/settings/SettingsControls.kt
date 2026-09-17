package com.absolutex.feature.settings

import android.content.pm.PackageManager
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.RotationLock
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // onCheckedChange is null: the row owns the toggle so TalkBack lands on one node.
        Switch(checked = checked, onCheckedChange = null)
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
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = groupDescription },
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(labelRes(option))) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            )
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // No contentDescription override: the merged title Text is the label, so every
            // fit-mode row announces its own name instead of the shared group description.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
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
        modifier = modifier,
    )
}

fun nightModeLabelRes(mode: NightMode): Int = when (mode) {
    NightMode.OFF -> R.string.settings_night_off
    NightMode.ON -> R.string.settings_night_on
    NightMode.SYSTEM -> R.string.settings_night_system
}

fun readingFlowLabelRes(flow: ReadingFlow): Int = when (flow) {
    ReadingFlow.LTR -> R.string.settings_flow_ltr
    ReadingFlow.RTL -> R.string.settings_flow_rtl
    ReadingFlow.VERTICAL -> R.string.settings_flow_vertical
}

fun fitModeLabelRes(mode: FitMode): Int = when (mode) {
    FitMode.FIT_SCREEN -> R.string.settings_fit_screen
    FitMode.FULL_SIZE -> R.string.settings_fit_full
    FitMode.FIT_WIDTH -> R.string.settings_fit_width
    FitMode.FIT_HEIGHT -> R.string.settings_fit_height
}

fun pageLayoutLabelRes(layout: PageLayout): Int = when (layout) {
    PageLayout.SINGLE -> R.string.settings_layout_single
    PageLayout.DOUBLE -> R.string.settings_layout_double
    PageLayout.DOUBLE_WITH_COVER -> R.string.settings_layout_cover
    PageLayout.CONTINUOUS_VERTICAL -> R.string.settings_layout_continuous
}

fun transitionLabelRes(transition: PageTransition): Int = when (transition) {
    PageTransition.SLIDE -> R.string.settings_transition_slide
    PageTransition.PAGE_OVER -> R.string.settings_transition_page_over
    PageTransition.REVEAL -> R.string.settings_transition_reveal
}

fun rotationLockLabelRes(lock: RotationLock): Int = when (lock) {
    RotationLock.SYSTEM -> R.string.settings_rotation_system
    RotationLock.PORTRAIT -> R.string.settings_rotation_portrait
    RotationLock.LANDSCAPE -> R.string.settings_rotation_landscape
}
