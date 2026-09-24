package com.absolutex.core.data.settings

import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler

/**
 * Rendering behaviour the reader draws with (§5.4, Rendering group).
 *
 * Milestone 2 carries draw-time colour correction; milestones 3–5 add the upscaler selection,
 * border crop and auto background to this same record. Global, not per book: per-book overrides
 * would need a second store keyed by book plus a UI surface for it, and the brief leaves them
 * optional — they stay out until a milestone owns them.
 */
data class RenderingPrefs(
    /** Draw-time colour correction (§4). Neutral by default: correction off costs nothing. */
    val colour: ColourParams = ColourParams(),
    /**
     * Which resampling filter magnifies a bitmap drawn above its base resolution (milestone 3).
     * PLATFORM is the hardware bilinear sampler and the default; the MITCHELL and LANCZOS kernel
     * choices refine the page at rest only, never mid-gesture.
     */
    val upscaler: Upscaler = Upscaler.PLATFORM,
    /**
     * Auto background colour (milestone 5): the letterbox tints to the page's own edge colour
     * instead of staying flat black. Off by default: a comic reads against black, and a page
     * with white margins would otherwise light the whole screen white. On is a settings choice.
     */
    val autoBackground: Boolean = false,
)
