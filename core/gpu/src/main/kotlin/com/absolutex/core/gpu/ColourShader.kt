package com.absolutex.core.gpu

/**
 * The draw-time colour shader (§4), AGSL.
 *
 * Applied per bitmap (base layer, then each visible tile) as a RuntimeShader on the tile Paint,
 * fed by one BitmapShader per bitmap — not as a RenderEffect on the page layer. A RenderEffect
 * draws the layer's contents into a separate offscreen layer first (RenderNode.setRenderEffect:
 * "the contents will be drawn in a separate layer", developer.android.com/reference/android/
 * graphics/RenderNode; the AGSL guide calls RenderEffect over a parent "more expensive than
 * drawing a custom View", developer.android.com/develop/ui/views/graphics/agsl/using-agsl).
 * At 1240×2772 that is a ~3.4 Mpx fullscreen extra pass plus the FBO, on top of work the tiled
 * renderer already culled. The Paint shader instead shades only the pixels actually drawn, with
 * uniforms updated in place and no layer either way.
 *
 * Direct transcription of [ColourMath.adjust] — white balance, contrast/brightness, gamma on
 * floored values, saturation fused with vibrance, explicit clamp. Keep the two in lockstep;
 * ColourMathTest pins both. Combined gamma is folded into the per-channel exponents on the CPU
 * ([ColourMath.foldedGamma]), so the shader does one pow per channel, not two.
 *
 * Two deliberate choices inside:
 *
 * - The shader performs no gamut clamp and no coloursource conversion of its own: the child
 *   BitmapShader is bound with setInputShader (colour-managed, unlike setInputBuffer), so a
 *   Display-P3 bitmap arrives in HWUI's working space intact and leaves graded but unclipped
 *   to sRGB. P3 preservation then hinges on the destination, which is the activity's colour
 *   mode — checked in the milestone 2 PR.
 * - Identity at neutral is within 1 ulp, not bit-exact — irrelevant in practice, because neutral
 *   never executes this program (see [ColourPipeline.shouldApply]).
 */
object ColourShader {

    const val UNIFORM_CONTENT = "content"
    const val UNIFORM_BRIGHTNESS = "brightness"
    const val UNIFORM_CONTRAST = "contrast"
    const val UNIFORM_SATURATION = "saturation"
    const val UNIFORM_TEMPERATURE = "temperature"
    const val UNIFORM_AGGRESSION = "aggression"
    const val UNIFORM_VIBRANCE = "vibrance"
    const val UNIFORM_GAMMA_EXP = "gammaExp"

    const val SOURCE = """uniform shader content;
uniform float brightness;
uniform float contrast;
uniform float saturation;
uniform float temperature;
uniform float aggression;
uniform float vibrance;
uniform vec3 gammaExp;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float WB_STRENGTH = 0.25;
vec4 main(vec2 fragCoord) {
    vec4 src = content.eval(fragCoord);
    float shift = temperature * aggression * WB_STRENGTH;
    vec3 c = src.rgb * vec3(1.0 + shift, 1.0, 1.0 - shift);
    c = (c - 0.5) * contrast + 0.5 + brightness;
    c = pow(max(c, vec3(0.0)), gammaExp);
    float luma = dot(c, LUMA);
    float deficit = 1.0 - clamp(max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b), 0.0, 1.0);
    float warmth = clamp((1.0 + c.r - c.b) / 2.0, 0.0, 1.0);
    float selectivity = deficit * mix(1.0, 0.35, warmth);
    c = mix(vec3(luma), c, saturation + vibrance * selectivity);
    return vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
}
