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
 * Two deliberate choices inside:
 *
 * - The shader performs no gamut clamp, so Display-P3 sources are not squeezed to sRGB; what is
 *   sampled is what is graded. Wide-gamut verification on the reference phone is milestone 2
 *   scope, alongside the colour-space-correct reading of hardware bitmaps.
 * - Identity at neutral is within 1 ulp, not bit-exact — irrelevant in practice, because neutral
 *   never executes this program (see [ColourPipeline.shouldApply]).
 */
object ColourShader {

    const val UNIFORM_CONTENT = "content"
    const val UNIFORM_BRIGHTNESS = "brightness"
    const val UNIFORM_CONTRAST = "contrast"
    const val UNIFORM_SATURATION = "saturation"

    // Direct transcription of ColourMath.adjust: contrast about mid-grey, additive brightness,
    // saturation as a luma blend, explicit clamp. Keep the two in lockstep; ColourMathTest pins it.
    const val SOURCE = """uniform shader content;
uniform float brightness;
uniform float contrast;
uniform float saturation;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
vec4 main(vec2 fragCoord) {
    vec4 src = content.eval(fragCoord);
    vec3 c = (src.rgb - 0.5) * contrast + 0.5 + brightness;
    float luma = dot(c, LUMA);
    c = mix(vec3(luma), c, saturation);
    return vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
}
