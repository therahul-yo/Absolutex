package com.absolutex.core.ui

import androidx.compose.ui.graphics.Color

/**
 * The palette is taken from the page, not from an app kit.
 *
 * Ink is true black: a comic's gutters are, and on an OLED panel black pixels are off. Panels step
 * up in flat greys the way panels sit on a page — elevation by tone, never by drop shadow. The one
 * accent is Caption, the process yellow of a comic's narration boxes, which carry black lettering;
 * it marks what is selected or primary and nothing else. Paper is off-white because pure white on
 * pure black glares at reading distance.
 */
object Ink {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF141414)
    val PanelRaised = Color(0xFF1F1F1F)
    val PanelHigh = Color(0xFF2A2A2A)
    val Rule = Color(0xFF3A3A3A)
    val Caption = Color(0xFFFFD23F)
    val CaptionDeep = Color(0xFF3D3000)
    val Paper = Color(0xFFF2EFE9)
    val PaperDim = Color(0xFFB7B2A8)
    val Alarm = Color(0xFFFF6B5B)
}
