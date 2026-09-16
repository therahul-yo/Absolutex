package com.absolutex.core.gpu

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The colour-correction chrome (§4, §5.2): one slider per [ColourParams] field, with a live
 * preview while dragging.
 *
 * Previewing at 120 fps works because the page never recomposes under a drag: the holder keeps
 * the params in snapshot state, passes that state to the page (see PageCanvas's `colour`), and
 * the page reads it in its draw scope — a drag repaints, nothing recomposes. This panel itself
 * is ordinary chrome and recomposes freely; keep it out of the page subtree.
 *
 * TODO(lead): host this in the reader chrome (§5.2 tap-center sheet) fed by RenderingPrefs, next
 * to the settings Rendering group which already hosts it. One host, one state object, no copies.
 */
@Composable
fun ColourPanel(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gpu_colour_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        ToneControls(state, onChange)
        WhiteBalanceControls(state, onChange)
        GradeRow(
            label = R.string.gpu_vibrance,
            value = state.vibrance,
            range = ColourParams.VIBRANCE_RANGE,
            onChange = { onChange(state.copy(vibrance = it)) },
        )
        GammaControls(state, onChange)
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
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_brightness,
            value = state.brightness,
            range = ColourParams.BRIGHTNESS_RANGE,
            onChange = { onChange(state.copy(brightness = it)) },
        )
        GradeRow(
            label = R.string.gpu_contrast,
            value = state.contrast,
            range = ColourParams.CONTRAST_RANGE,
            onChange = { onChange(state.copy(contrast = it)) },
        )
        GradeRow(
            label = R.string.gpu_saturation,
            value = state.saturation,
            range = ColourParams.SATURATION_RANGE,
            onChange = { onChange(state.copy(saturation = it)) },
        )
    }
}

/** White balance with its aggressiveness control. */
@Composable
private fun WhiteBalanceControls(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_temperature,
            value = state.temperature,
            range = ColourParams.TEMPERATURE_RANGE,
            onChange = { onChange(state.copy(temperature = it)) },
        )
        GradeRow(
            label = R.string.gpu_aggression,
            value = state.wbAggression,
            range = ColourParams.AGGRESSION_RANGE,
            onChange = { onChange(state.copy(wbAggression = it)) },
        )
    }
}

/** Combined gamma plus the per-channel exponents. */
@Composable
private fun GammaControls(
    state: ColourParams,
    onChange: (ColourParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GradeRow(
            label = R.string.gpu_gamma,
            value = state.gamma,
            range = ColourParams.GAMMA_RANGE,
            onChange = { onChange(state.copy(gamma = it)) },
        )
        GradeRow(
            label = R.string.gpu_gamma_r,
            value = state.gammaR,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaR = it)) },
        )
        GradeRow(
            label = R.string.gpu_gamma_g,
            value = state.gammaG,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaG = it)) },
        )
        GradeRow(
            label = R.string.gpu_gamma_b,
            value = state.gammaB,
            range = ColourParams.GAMMA_CHANNEL_RANGE,
            onChange = { onChange(state.copy(gammaB = it)) },
        )
    }
}

/** One labelled slider. Stateless and controlled: every tick calls [onChange] for live preview. */
@Composable
private fun GradeRow(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(label)
    val valueText = "%.2f".format(value)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $valueText"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(136.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}
