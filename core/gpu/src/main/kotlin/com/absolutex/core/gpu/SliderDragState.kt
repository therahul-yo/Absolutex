package com.absolutex.core.gpu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** The thumb follows the gesture, not delayed persisted values. Both modes commit the final value. */
internal class SliderDragState(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    private var dragging = false

    fun sync(persisted: Float) {
        if (!dragging) value = persisted
    }

    fun change(next: Float, live: Boolean, commit: (Float) -> Unit) {
        dragging = true
        value = next
        if (live) commit(next)
    }

    fun finish(commit: (Float) -> Unit) {
        if (dragging) {
            commit(value)
            dragging = false
        }
    }
}
