package com.absolutex.feature.reader

import android.view.KeyEvent
import com.absolutex.feature.reader.ReaderKeyAction.BACK
import com.absolutex.feature.reader.ReaderKeyAction.FIRST_PAGE
import com.absolutex.feature.reader.ReaderKeyAction.LAST_PAGE
import com.absolutex.feature.reader.ReaderKeyAction.NEXT_PAGE
import com.absolutex.feature.reader.ReaderKeyAction.PREVIOUS_PAGE
import com.absolutex.feature.reader.ReaderKeyAction.TOGGLE_CHROME
import com.absolutex.feature.reader.ReaderKeyAction.ZOOM_IN
import com.absolutex.feature.reader.ReaderKeyAction.ZOOM_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderKeysTest {

    private fun act(key: Int, shift: Boolean = false, rtl: Boolean = false, volume: Boolean = false) =
        ReaderKeys.actionFor(key, shift, rtl, volume)

    @Test fun `arrows follow the screen, so a right-to-left book reverses them`() {
        assertEquals(NEXT_PAGE, act(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PREVIOUS_PAGE, act(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PREVIOUS_PAGE, act(KeyEvent.KEYCODE_DPAD_RIGHT, rtl = true))
        assertEquals(NEXT_PAGE, act(KeyEvent.KEYCODE_DPAD_LEFT, rtl = true))
    }

    @Test fun `page keys, space and gamepad A go forward in book order whatever the flow`() {
        val forward = listOf(
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_DOWN,
        )
        forward.forEach { assertEquals(NEXT_PAGE, act(it, rtl = true)) }
        assertEquals(PREVIOUS_PAGE, act(KeyEvent.KEYCODE_SPACE, shift = true))
        assertEquals(PREVIOUS_PAGE, act(KeyEvent.KEYCODE_PAGE_UP, rtl = true))
    }

    @Test fun `home and end jump to the ends of the book`() {
        assertEquals(FIRST_PAGE, act(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(LAST_PAGE, act(KeyEvent.KEYCODE_MOVE_END))
    }

    @Test fun `zoom, chrome and back are reachable from keyboard and gamepad alike`() {
        assertEquals(ZOOM_IN, act(KeyEvent.KEYCODE_PLUS))
        assertEquals(ZOOM_IN, act(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(ZOOM_OUT, act(KeyEvent.KEYCODE_MINUS))
        assertEquals(ZOOM_OUT, act(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(TOGGLE_CHROME, act(KeyEvent.KEYCODE_ENTER))
        assertEquals(TOGGLE_CHROME, act(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(BACK, act(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(BACK, act(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test fun `volume keys stay the system's unless the setting hands them over`() {
        assertNull(act(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(act(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(NEXT_PAGE, act(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true))
        assertEquals(PREVIOUS_PAGE, act(KeyEvent.KEYCODE_VOLUME_UP, volume = true))
    }

    @Test fun `unrelated keys are left alone`() {
        assertNull(act(KeyEvent.KEYCODE_A))
        assertNull(act(KeyEvent.KEYCODE_CAMERA))
    }
}
