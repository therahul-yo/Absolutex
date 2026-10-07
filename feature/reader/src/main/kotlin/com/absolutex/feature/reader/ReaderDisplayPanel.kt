package com.absolutex.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.gpu.ColourPanel
import com.absolutex.core.gpu.Upscaler
import com.absolutex.core.ui.A11y

/** Leaves the upper page visible even while this ten-slider panel scrolls. */
internal fun readerChromeHeightFraction(display: Boolean): Float = if (display) DISPLAY_HEIGHT else CHROME_MAX_HEIGHT
private const val DISPLAY_HEIGHT = 0.55f

@Composable
internal fun ReaderDisplayEntry(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.defaultMinSize(minHeight = A11y.MinTouchTarget)) {
        Text(stringResource(R.string.reader_display))
    }
}

@Composable
internal fun ReaderDisplayPanel(onBack: () -> Unit, vm: ReaderDisplayViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    TextButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = A11y.MinTouchTarget)) {
        Text(stringResource(R.string.reader_display_back))
    }
    Text(stringResource(R.string.reader_display), style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = A11y.MinTouchTarget)
            .toggleable(value = prefs.enhanceEnabled, role = Role.Switch) { vm.toggle() }
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.reader_enhance), modifier = Modifier.weight(1f))
        Switch(checked = prefs.enhanceEnabled, onCheckedChange = null)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Upscaler.entries.forEach { upscaler ->
            FilterChip(
                selected = prefs.upscaler == upscaler,
                onClick = { vm.setUpscaler(upscaler) },
                label = { Text(stringResource(displayUpscalerLabel(upscaler))) },
                modifier = Modifier.defaultMinSize(minHeight = A11y.MinTouchTarget),
            )
        }
    }
    if (prefs.enhanceEnabled) Text(
        stringResource(R.string.reader_display_enhance_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    ColourPanel(state = prefs.colour, onChange = vm::setColour, liveUpdates = true)
}

private fun displayUpscalerLabel(upscaler: Upscaler): Int = when (upscaler) {
    Upscaler.PLATFORM -> R.string.reader_upscaler_platform
    Upscaler.MITCHELL -> R.string.reader_upscaler_mitchell
    Upscaler.LANCZOS -> R.string.reader_upscaler_lanczos
}
