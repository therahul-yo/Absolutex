package com.absolutex.core.data.settings

import com.absolutex.core.gpu.ColourParams

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
)
