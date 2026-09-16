package com.absolutex.core.gpu

/**
 * The colour-correction strengths applied at draw time (§4).
 *
 * Milestone 1 carries the three adjustments with no plausible dispute about their meaning;
 * milestone 2 adds white balance (+ aggressiveness), vibrance/selective saturation and the
 * per-channel and combined gamma controls to this same record and shader.
 *
 * Ranges are documented, not enforced: the settings UI clamps, and the shader stays total over
 * any float the benchmark harness cares to throw at it. All maths happens in the encoded (sRGB)
 * space the bitmap is sampled in — the same space the AGSL transcribes — so the JVM reference
 * in [ColourMath] and the GPU agree by construction.
 */
data class ColourParams(
    /** Additive lift after contrast, in encoded units. 0 is identity. */
    val brightness: Float = 0f,
    /** Pivot about mid-grey. 1 is identity; 0 is flat grey. */
    val contrast: Float = 1f,
    /** Blend between luma and the colour. 1 is identity; 0 is monochrome. */
    val saturation: Float = 1f,
) {
    /** True when the draw path must be bit-for-bit today's: no layer, no shader. */
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f

    /**
     * Compact serialisation for the Macrobenchmark intent hook
     * (see [EXTRA_COLOUR]): `b=0.15,c=1.1,s=1.25`.
     */
    fun encode(): String = "b=$brightness,c=$contrast,s=$saturation"

    companion object {
        val NEUTRAL = ColourParams()

        /**
         * Intent extra carrying [encode] output. PageCanvas honours it so GpuBenchmark can drive
         * the corrected path with no settings UI and no edits outside the GPU workstream's files.
         * Milestone 2's RenderingPrefs wiring replaces this hook.
         */
        const val EXTRA_COLOUR = "com.absolutex.gpu.COLOUR"

        /** Encoded fields per extra: exactly b, c and s, nothing else. */
        private const val FIELD_COUNT = 3

        /** Null on any corrupt input, so a bad extra falls back to the idle path, never a crash. */
        fun decode(raw: String?): ColourParams? {
            val fields = raw.orEmpty().split(',')
                .map { it.split('=', limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to it[1].toFloatOrNull()?.takeIf(Float::isFinite) }
            val ordered = listOf(fields["b"], fields["c"], fields["s"]).filterNotNull()
            return if (fields.size == FIELD_COUNT && ordered.size == FIELD_COUNT) {
                ColourParams(ordered[0], ordered[1], ordered[2])
            } else {
                null
            }
        }
    }
}
