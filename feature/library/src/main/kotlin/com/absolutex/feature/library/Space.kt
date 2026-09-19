package com.absolutex.feature.library

import androidx.compose.ui.unit.dp

/**
 * Shared spacing. Named rather than sprinkled, so the screens stay on one rhythm.
 *
 * The 48 dp touch-target floor used to live here. It is an accessibility invariant rather
 * than this module's spacing taste, so it now sits in [com.absolutex.core.ui.A11y] where
 * every lane can reference it.
 */
internal object Space {
    val Edge = 16.dp
    val Row = 12.dp
    val Tight = 4.dp
    val Zero = 0.dp
}
