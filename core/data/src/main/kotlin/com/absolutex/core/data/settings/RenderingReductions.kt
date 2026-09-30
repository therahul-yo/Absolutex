package com.absolutex.core.data.settings

import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler

/** An advanced edit updates the saved custom choice and turns the preset off in the same edit. */
fun RenderingPrefs.withColour(colour: ColourParams): RenderingPrefs =
    copy(colour = colour.clamped(), enhanceEnabled = false)

fun RenderingPrefs.withUpscaler(upscaler: Upscaler): RenderingPrefs = copy(upscaler = upscaler, enhanceEnabled = false)
