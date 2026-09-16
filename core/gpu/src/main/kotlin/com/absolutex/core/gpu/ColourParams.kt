package com.absolutex.core.gpu

/**
 * The colour-correction strengths applied at draw time (§4).
 *
 * Stages run in a fixed order, in [ColourMath] and the AGSL alike: white balance, contrast
 * about mid-grey with additive brightness, a floor at zero, per-channel gamma (combined folded
 * in), then saturation fused with vibrance, and a final clamp. Every stage is an exact no-op
 * at its default, so neutral still never enters the shader (see [ColourPipeline.shouldApply]).
 *
 * Ranges are the single source of truth for the settings sliders ([ColourPanel]) and the
 * persisted store ([PrefCodec] clamps to these): the shader itself stays total over any float.
 * All maths happens in the encoded (sRGB) space the bitmap is sampled in.
 */
data class ColourParams(
    /** Additive lift after contrast, in encoded units. 0 is identity. */
    val brightness: Float = 0f,
    /** Pivot about mid-grey. 1 is identity; 0 is flat grey. */
    val contrast: Float = 1f,
    /** Blend between luma and the colour. 1 is identity; 0 is monochrome. */
    val saturation: Float = 1f,
    /**
     * White balance along the amber–blue axis: positive warms (red up, blue down), negative
     * cools. 0 is identity, scaled by [wbAggression].
     */
    val temperature: Float = 0f,
    /**
     * How much of [temperature] applies. 1 is the full shift; 0 disables white balance
     * whatever the temperature. Excluded from [isNeutral]: with no temperature it does nothing.
     */
    val wbAggression: Float = 1f,
    /**
     * Selective saturation: boosts muted pixels proportionally to their saturation deficit,
     * with warm hues (skin) protected relative to cool ones (sky, foliage). 0 disables it.
     */
    val vibrance: Float = 0f,
    /** Combined gamma, folded with the per-channel exponents. 1 is identity. */
    val gamma: Float = 1f,
    /** Per-channel gamma for cast correction (applied as combined × channel). 1 is identity. */
    val gammaR: Float = 1f,
    val gammaG: Float = 1f,
    val gammaB: Float = 1f,
) {
    /**
     * True when the draw path must be bit-for-bit today's: no layer, no shader. Aggression is
     * deliberately absent: it only scales a zero temperature.
     */
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f &&
            temperature == 0f && vibrance == 0f &&
            gamma == 1f && gammaR == 1f && gammaG == 1f && gammaB == 1f

    /**
     * Clamp-then-write: a corrupt store self-heals on the next edit, following the cache-size
     * precedent in PrefCodec. The panel never needs this (Slider enforces its range), but
     * programmatic callers do.
     */
    fun clamped(): ColourParams = copy(
        brightness = brightness.coerceIn(BRIGHTNESS_RANGE),
        contrast = contrast.coerceIn(CONTRAST_RANGE),
        saturation = saturation.coerceIn(SATURATION_RANGE),
        temperature = temperature.coerceIn(TEMPERATURE_RANGE),
        wbAggression = wbAggression.coerceIn(AGGRESSION_RANGE),
        vibrance = vibrance.coerceIn(VIBRANCE_RANGE),
        gamma = gamma.coerceIn(GAMMA_RANGE),
        gammaR = gammaR.coerceIn(GAMMA_CHANNEL_RANGE),
        gammaG = gammaG.coerceIn(GAMMA_CHANNEL_RANGE),
        gammaB = gammaB.coerceIn(GAMMA_CHANNEL_RANGE),
    )

    /**
     * Compact serialisation for the Macrobenchmark intent hook
     * (see [EXTRA_COLOUR]): `b=…,c=…,s=…,t=…,a=…,v=…,g=…,gr=…,gg=…,gb=…`.
     */
    fun encode(): String =
        "b=$brightness,c=$contrast,s=$saturation,t=$temperature,a=$wbAggression," +
            "v=$vibrance,g=$gamma,gr=$gammaR,gg=$gammaG,gb=$gammaB"

    companion object {
        val NEUTRAL = ColourParams()

        /**
         * Intent extra carrying [encode] output. PageCanvas honours it so benchmarks drive the
         * corrected path with no settings UI; production feeds the `colour` parameter from
         * RenderingPrefs instead.
         */
        const val EXTRA_COLOUR = "com.absolutex.gpu.COLOUR"

        val BRIGHTNESS_RANGE = -1f..1f
        val CONTRAST_RANGE = 0f..2f
        val SATURATION_RANGE = 0f..2f
        val TEMPERATURE_RANGE = -1f..1f
        val AGGRESSION_RANGE = 0f..1f
        val VIBRANCE_RANGE = 0f..1f
        val GAMMA_RANGE = 0.5f..2.5f
        val GAMMA_CHANNEL_RANGE = 0.5f..2f

        /**
         * Null on any corrupt input, so a bad extra falls back to the idle path, never a crash.
         * Absent keys resolve to their defaults (the PrefCodec convention); unknown keys or
         * non-finite values reject the whole extra.
         */
        fun decode(raw: String?): ColourParams? {
            if (raw.isNullOrBlank()) return null
            val fields = raw.split(',')
                .map { it.split('=', limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to it[1].toFloatOrNull()?.takeIf(Float::isFinite) }
            val known = setOf("b", "c", "s", "t", "a", "v", "g", "gr", "gg", "gb")
            val defaults = NEUTRAL
            return if (fields.all { (key, value) -> key in known && value != null }) {
                ColourParams(
                    brightness = fields["b"] ?: defaults.brightness,
                    contrast = fields["c"] ?: defaults.contrast,
                    saturation = fields["s"] ?: defaults.saturation,
                    temperature = fields["t"] ?: defaults.temperature,
                    wbAggression = fields["a"] ?: defaults.wbAggression,
                    vibrance = fields["v"] ?: defaults.vibrance,
                    gamma = fields["g"] ?: defaults.gamma,
                    gammaR = fields["gr"] ?: defaults.gammaR,
                    gammaG = fields["gg"] ?: defaults.gammaG,
                    gammaB = fields["gb"] ?: defaults.gammaB,
                )
            } else {
                null
            }
        }
    }
}
