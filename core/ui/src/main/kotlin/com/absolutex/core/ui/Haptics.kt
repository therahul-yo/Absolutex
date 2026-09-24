package com.absolutex.core.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics with names that say when to use them. Every call goes through the view, so the
 * system's "touch feedback" setting is respected without a branch here.
 */
class Haptics(private val view: View) {
    /** A light tap, for a control that was pressed. */
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A crisp click, for a choice that changed something: a tab, a toggle, a sort. */
    fun select() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** The confirming pulse, for something that was done: a selection started, a save. */
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
