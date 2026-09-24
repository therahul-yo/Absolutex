package com.absolutex.feature.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A whole-number setting on a slider, labelled with its own value.
 *
 * Tracks the thumb during a drag so the store sees one write per gesture, not one per frame;
 * the commit rides on release. Keyed on [value] so an external change still overrides an unmoved
 * thumb. Same contract as the cache-size row, which is why they share this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberSliderRow(
    valueLabelRes: Int,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    descriptionRes: Int,
    modifier: Modifier = Modifier,
) {
    var pending by remember(value) { mutableIntStateOf(value) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(valueLabelRes, pending),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val description = stringResource(descriptionRes, pending)
        Slider(
            // No stop-indicator dot at the track's end: it marks nothing here.
            track = { SliderDefaults.Track(it, drawStopIndicator = null) },
            value = pending.toFloat(),
            onValueChange = { pending = it.roundToInt() },
            onValueChangeFinished = { onChange(pending) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = description },
        )
    }
}
