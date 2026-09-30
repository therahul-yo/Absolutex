package com.absolutex.feature.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics

/** Bitmap/PDF chrome only; the text EPUB bar never supplies this control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnhanceButton(
    initialEnabled: Boolean = false,
    vm: EnhanceViewModel = hiltViewModel(),
) {
    val on by vm.enabled.collectAsStateWithLifecycle(initialEnabled)
    val label = stringResource(R.string.reader_enhance)
    val state = stringResource(if (on) R.string.reader_enhance_on else R.string.reader_enhance_off)
    val ink = MaterialTheme.colorScheme.onSurface
    val container by animateColorAsState(
        if (on) ink.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
        animationSpec = tween(Motion.SHORT_MS), label = "enhance_container",
    )
    val haptics = rememberHaptics()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconToggleButton(
            checked = on,
            onCheckedChange = { haptics.select(); vm.toggle() },
            modifier = Modifier.size(48.dp).semantics { stateDescription = state },
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = container, checkedContainerColor = container,
                contentColor = ink, checkedContentColor = ink,
            ),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

private const val SELECTED_ALPHA = 0.12f
