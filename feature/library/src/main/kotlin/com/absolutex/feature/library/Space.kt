package com.absolutex.feature.library

import androidx.compose.ui.unit.dp

/** Shared spacing. Named rather than sprinkled, so the screens stay on one rhythm. */
internal object Space {
    val Edge = 16.dp
    val Row = 12.dp
    val Tight = 4.dp
    val Zero = 0.dp

    /** §7: nothing interactive is smaller than this, whether or not TalkBack is on. */
    val MinTouchTarget = 48.dp
}
