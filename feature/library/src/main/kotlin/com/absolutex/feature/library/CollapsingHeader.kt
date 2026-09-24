package com.absolutex.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * The library's header — title bar and the search/sort/view row — as one block that slides away
 * while the shelf scrolls down and returns the moment it scrolls up (Material's enter-always
 * behaviour), giving the covers the whole screen while browsing.
 *
 * It collapses by its own measured height, which [scroll] learns here, so the title bar and the
 * row go together rather than the bar alone. The status-bar inset stays outside the collapsing
 * part: fully collapsed, covers pass under the status bar's own background, never under the clock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollapsingHeader(scroll: TopAppBarScrollBehavior, content: @Composable () -> Unit) {
    Column(
        Modifier
            .statusBarsPadding()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                scroll.state.heightOffsetLimit = -placeable.height.toFloat()
                val height = (placeable.height + scroll.state.heightOffset).roundToInt().coerceAtLeast(0)
                layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
            },
    ) { content() }
}
