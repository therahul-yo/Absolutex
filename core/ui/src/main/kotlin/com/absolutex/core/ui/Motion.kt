package com.absolutex.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One motion vocabulary, so every moving thing in the app agrees on how things move.
 *
 * Everything here answers a touch — nothing animates on its own. Compose scales every duration by
 * the system animator setting, so "Remove animations" in accessibility settings is respected
 * without a branch here.
 */
object Motion {
    /** Material's emphasized curve: fast out, long settle. For things entering. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For things leaving: accelerate away, no settle to wait for. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT_MS = 100
    const val MEDIUM_MS = 200
    const val LONG_MS = 300

    /** How far a screen travels on a shared-axis transition, as a fraction of its width. */
    const val SHARED_AXIS_FRACTION = 10

    /** How much a pressed control gives: enough to feel, not enough to read as a layout jump. */
    const val PRESS_SCALE = 0.96f

    fun <T> enter() = tween<T>(MEDIUM_MS, easing = Emphasized)
    fun <T> exit() = tween<T>(SHORT_MS, easing = EmphasizedAccelerate)
    /** Critically damped and stiff: a control settles at once, with no wobble to wait out. */
    fun <T> press() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
}
