package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderDragStateTest {
    @Test fun `live mode commits samples and the exact final value`() {
        val drag = SliderDragState(0f)
        val commits = mutableListOf<Float>()
        drag.change(0.2f, live = true, commit = { commits.add(it) })
        assertEquals(listOf(0.2f), commits)
        drag.change(0.7f, live = true, commit = { commits.add(it) })
        drag.finish { commits.add(it) }
        assertEquals(listOf(0.2f, 0.7f, 0.7f), commits)
    }

    @Test fun `release mode moves the thumb but writes only on finish`() {
        val drag = SliderDragState(0f)
        val commits = mutableListOf<Float>()
        drag.change(0.2f, live = false, commit = { commits.add(it) })
        drag.change(0.7f, live = false, commit = { commits.add(it) })
        assertEquals(0.7f, drag.value)
        assertEquals(emptyList<Float>(), commits)
        drag.finish { commits.add(it) }
        drag.finish { commits.add(it) }
        assertEquals(listOf(0.7f), commits)
    }

    @Test fun `lagging store emissions cannot move a held thumb`() {
        val drag = SliderDragState(0f)
        drag.sync(0.1f)
        assertEquals(0.1f, drag.value)
        drag.change(0.8f, live = true, commit = {})
        drag.sync(0.2f)
        assertEquals(0.8f, drag.value)
        drag.finish {}
        drag.sync(0.9f)
        assertEquals(0.9f, drag.value)
    }
}
