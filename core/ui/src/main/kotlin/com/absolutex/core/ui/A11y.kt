package com.absolutex.core.ui

import androidx.compose.ui.unit.dp

/**
 * Accessibility invariants every surface has to hold to, wherever it is drawn.
 *
 * These live in `:core:ui` rather than in one feature because they are not that feature's
 * taste — they are the floor the whole app is measured against, and a rule only one module
 * can name is a rule the other five quietly re-derive or omit.
 */
object A11y {

    /**
     * §7: nothing interactive is smaller than this, whether or not TalkBack is on.
     *
     * Material 3's own controls already reserve a 48 dp touch target through
     * `minimumInteractiveComponentSize()`, even where the control is drawn smaller — those do
     * not need this. It is for everything else: a bare `Modifier.clickable`, a custom row, any
     * tap target this app builds itself.
     */
    val MinTouchTarget = 48.dp
}
