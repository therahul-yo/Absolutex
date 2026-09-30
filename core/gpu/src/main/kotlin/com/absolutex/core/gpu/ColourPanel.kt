package com.absolutex.core.gpu

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shared colour controls. Settings commits on release; reader hosts opt into [liveUpdates]
 * so the visible page observes the same persisted preferences throughout a drag.
 */
@Composable
fun ColourPanel(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdates: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gpu_colour_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        ToneControls(state, onChange, liveUpdates = liveUpdates)
        WhiteBalanceControls(state, onChange, liveUpdates = liveUpdates)
        GradeRow(
            label = R.string.gpu_vibrance,
            value = state.vibrance,
            range = ColourParams.VIBRANCE_RANGE,
            onChange = { onChange(state.copy(vibrance = it)) },
            liveUpdates = liveUpdates,
        )
        GammaControls(state, onChange, liveUpdates = liveUpdates)
        TextButton(
            onClick = { onChange(ColourParams.NEUTRAL) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.gpu_reset))
        }
    }
}

/** Brightness, contrast and saturation: the tone section. */
@Composable
private fun ToneControls(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdates: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_brightness,
            value = state.brightness,
            range = ColourParams.BRIGHTNESS_RANGE,
            onChange = { onChange(state.copy(brightness = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_contrast,
            value = state.contrast,
            range = ColourParams.CONTRAST_RANGE,
            onChange = { onChange(state.copy(contrast = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_saturation,
            value = state.saturation,
            range = ColourParams.SATURATION_RANGE,
            onChange = { onChange(state.copy(saturation = it)) },
            liveUpdates = liveUpdates,
        )
    }
}

/** White balance with its aggressiveness control. */
@Composable
private fun WhiteBalanceControls(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdates: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_temperature,
            value = state.temperature,
            range = ColourParams.TEMPERATURE_RANGE,
            onChange = { onChange(state.copy(temperature = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_aggression,
            value = state.wbAggression,
            range = ColourParams.AGGRESSION_RANGE,
            onChange = { onChange(state.copy(wbAggression = it)) },
            liveUpdates = liveUpdates,
        )
    }
}

/** Combined gamma plus the per-channel exponents. */
@Composable
private fun GammaControls(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdates: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_gamma,
            value = state.gamma,
            range = ColourParams.GAMMA_RANGE,
            onChange = { onChange(state.copy(gamma = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_gamma_r,
            value = state.gammaR,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaR = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_gamma_g,
            value = state.gammaG,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaG = it)) },
            liveUpdates = liveUpdates,
        )
        GradeRow(
            label = R.string.gpu_gamma_b,
            value = state.gammaB,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaB = it)) },
            liveUpdates = liveUpdates,
        )
    }
}

/**
 * Commit-on-finish coalescing, shared by [GradeRow] and its test.
 *
 * A drag emits many [Change] events (one per pointer sample) and exactly one [Finish] at the
 * end. This collapses that stream to the committed value — one write per gesture, not one per
 * frame — so the DataStore never sees a backlog of identical writes and the thumb never lags
 * the pointer. See §4 (live preview at 120 fps while dragging) and the #23 review, item 1.
 */
internal fun coalesceSlider(events: List<SliderEvent>): List<Float> {
    val commits = mutableListOf<Float>()
    var pending = Float.NaN
    for (e in events) when (e) {
        is SliderEvent.Change -> pending = e.value
        is SliderEvent.Finish -> {
            // Commit the latest dragged value, then clear it so a later bare Finish (no
            // intervening Change) does not re-commit the same value. One write per gesture.
            if (!pending.isNaN()) {
                commits.add(pending)
                pending = Float.NaN
            }
        }
    }
    return commits
}

/**
 * How a grade row divides between its label and its slider.
 *
 * A fraction, not the 136.dp this used to be. The fixed width bought one thing worth keeping —
 * every slider track starting at the same x, because a column of sliders with ragged tracks
 * reads as broken — and it bought it at the cost of pinning the label to a size chosen at one
 * font scale. At 200% "White-balance strength" cannot fit 136.dp and stacks into several lines,
 * making that row several times taller than its neighbours.
 *
 * Every row is the same width, so the same fraction gives every label the same width: the
 * alignment survives, and the label is free to wrap rather than being sized for one scale.
 *
 * `widthIn(min = 136.dp)` does not work here, which is worth recording. Row measures its
 * unweighted children before its weighted one, so a Text with a minimum and no maximum takes
 * its own intrinsic width — "Gamma" would settle at 136.dp and "White-balance strength" at
 * whatever it needs, and the tracks would part company at default scale, not just at 200%.
 * `IntrinsicSize.Max` does not fix that either: each row here is an independent [Row], so the
 * maximum it resolves is that one label's, never the widest of the ten.
 *
 * 0.4 is where 136.dp already sat on the reference device's panel, so this is not a visible
 * change at the scale everything was drawn for.
 */
private const val LABEL_WEIGHT = 0.4f
private const val SLIDER_WEIGHT = 0.6f

internal sealed interface SliderEvent {
    data class Change(val value: Float) : SliderEvent
    data object Finish : SliderEvent
}

/**
 * One labelled slider. Reader hosts propagate changes during dragging; Settings commits on release.
 *
 * Keyed on [value] so an external change (e.g. restoring a saved value) still overrides an
 * unmoved thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeRow(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    liveUpdates: Boolean = false,
) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    val name = stringResource(label)
    val valueText = "%.2f".format(pending)
    // Read here, not inside the semantics lambda: that lambda is not composable.
    val description = stringResource(R.string.gpu_grade_row_desc, name, valueText)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            // No maxLines: a control name that is clipped is unusable sighted and useless with
            // TalkBack. It wraps instead, and the row grows to fit.
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Slider(
            // No stop-indicator dot at the track's end: it marks nothing here.
            track = { SliderDefaults.Track(it, drawStopIndicator = null) },
            value = pending,
            onValueChange = { pending = it; if (liveUpdates) onChange(it) },
            onValueChangeFinished = { if (!liveUpdates) onChange(pending) },
            valueRange = range,
            modifier = Modifier.weight(SLIDER_WEIGHT),
        )
    }
}
