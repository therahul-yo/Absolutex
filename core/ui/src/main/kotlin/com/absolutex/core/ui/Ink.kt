package com.absolutex.core.ui

import androidx.compose.ui.graphics.Color

/**
 * A monochrome palette: black, greys and white, nothing else.
 *
 * Minimal on purpose — the covers and the pages are the only colour on screen. Black is true
 * black, so on an OLED panel the background is off. Surfaces step up in flat greys: elevation by
 * tone, never by shadow. The accent is plain white; selection reads as the brightest thing on the
 * screen, with black on it. Paper is a touch off pure white, which glares on black.
 */
object Ink {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF121212)
    val PanelRaised = Color(0xFF1C1C1C)
    val PanelHigh = Color(0xFF262626)
    val Rule = Color(0xFF3A3A3A)
    val Accent = Color(0xFFF5F5F5)
    val AccentDeep = Color(0xFF303030)
    val Paper = Color(0xFFEDEDED)
    val PaperDim = Color(0xFF9E9E9E)
    val Alarm = Color(0xFFFF6B5B)
}
