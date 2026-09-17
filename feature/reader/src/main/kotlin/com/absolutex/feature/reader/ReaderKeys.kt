package com.absolutex.feature.reader

import android.view.KeyEvent
import com.absolutex.model.TapGrid
import com.absolutex.model.TapZone
import com.absolutex.model.column

/** What a key asks the reader to do (§5.3). */
enum class ReaderKeyAction { NEXT_PAGE, PREVIOUS_PAGE, FIRST_PAGE, LAST_PAGE, ZOOM_IN, ZOOM_OUT, TOGGLE_CHROME, BACK }

/**
 * §5.3: keyboard and gamepad must be fully sufficient without touching the screen.
 *
 * Arrows are screen-relative: → shows what is to the right, which in a right-to-left book is the
 * previous page. Everything else is in book order: Page Down, Space and the gamepad's A always go
 * forward, whichever way the book reads.
 *
 * Not yet: F1/F2 previous/next book (needs the library), D-pad scrolling a zoomed page, mouse wheel.
 */
/**
 * What a tap in §5.2's grid does: the outer columns turn pages — the one way to turn that never
 * competes with the pager, which matters on a zoomed page where the pager is switched off — and the
 * centre column toggles the chrome. Shared by every layout, so they cannot drift apart.
 */
fun onTapZone(zone: TapZone, step: (Int) -> Unit, toggleChrome: () -> Unit) {
    when (zone.column) {
        TapGrid.CELLS - 1 -> step(1)
        0 -> step(-1)
        else -> toggleChrome()
    }
}

object ReaderKeys {

    @Suppress("CyclomaticComplexMethod")
    fun actionFor(keyCode: Int, shift: Boolean, rightToLeft: Boolean, volumeKeys: Boolean): ReaderKeyAction? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (rightToLeft) ReaderKeyAction.PREVIOUS_PAGE else ReaderKeyAction.NEXT_PAGE
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (rightToLeft) ReaderKeyAction.NEXT_PAGE else ReaderKeyAction.PREVIOUS_PAGE
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_A,
            -> ReaderKeyAction.NEXT_PAGE
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_BUTTON_X,
            -> ReaderKeyAction.PREVIOUS_PAGE
            KeyEvent.KEYCODE_SPACE -> if (shift) ReaderKeyAction.PREVIOUS_PAGE else ReaderKeyAction.NEXT_PAGE
            KeyEvent.KEYCODE_MOVE_HOME -> ReaderKeyAction.FIRST_PAGE
            KeyEvent.KEYCODE_MOVE_END -> ReaderKeyAction.LAST_PAGE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU,
            -> ReaderKeyAction.TOGGLE_CHROME
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
            -> ReaderKeyAction.ZOOM_IN
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
            -> ReaderKeyAction.ZOOM_OUT
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BUTTON_B -> ReaderKeyAction.BACK
            // Volume down is forward, as in every reader that offers this; unmapped when the
            // setting is off, so the system keeps its volume keys.
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) ReaderKeyAction.NEXT_PAGE else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) ReaderKeyAction.PREVIOUS_PAGE else null
            else -> null
        }
}
