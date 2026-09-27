package com.absolutex.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics

/**
 * The phone's section switcher: a dark capsule floating above the bottom edge, the current
 * section a light pill with its name, the others icons alone. Content scrolls under it, so the
 * covers keep the whole screen; lists pad their end by [FloatingBarClearance] to scroll clear.
 *
 * Monochrome like the rest of the app: the pill is the one light thing in the bar. The pill's
 * width change is the only motion, fast and without a bounce.
 */
@Composable
internal fun FloatingNavBar(
    selected: HomeSection,
    onSelect: (HomeSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier.navigationBarsPadding().padding(bottom = BarMargin),
    ) {
        Row(
            Modifier.padding(BarInset).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(BarInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeSection.entries.forEach { section ->
                val isSelected = section == selected
                NavPill(section, isSelected) {
                    if (!isSelected) haptics.select()
                    onSelect(section)
                }
            }
        }
    }
}

@Composable
private fun NavPill(section: HomeSection, selected: Boolean, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colours.onSurface else colours.surfaceContainerHigh,
        Motion.enter(),
        label = "pill",
    )
    val ink = if (selected) colours.surface else colours.onSurface
    val label = section.label()
    Row(
        Modifier
            .height(A11y.MinTouchTarget)
            .clip(CircleShape)
            .background(fill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            // The icon alone names nothing: the tab's name is its description, selected or not.
            .semantics { contentDescription = label }
            .padding(horizontal = PillPadding)
            .animateContentSize(Motion.enter()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon(selected), contentDescription = null, tint = ink)
        AnimatedVisibility(
            selected,
            enter = fadeIn(Motion.enter()) + expandHorizontally(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
        ) {
            Text(
                label.uppercase(),
                color = ink,
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.8.sp),
                maxLines = 1,
                modifier = Modifier.padding(start = LabelGap),
            )
        }
    }
}

/** How far a list must pad its end to scroll clear of the floating bar; zero without one. */
internal val LocalFloatingBarClearance = compositionLocalOf { 0.dp }

private val BarMargin = 14.dp
private val BarInset = 5.dp
private val PillPadding = 14.dp
private val LabelGap = 8.dp

/** The bar's height above the navigation bar, plus its margin: what the content clears. */
internal val FloatingBarClearance: Dp = A11y.MinTouchTarget + BarInset * 2 + BarMargin + 8.dp
