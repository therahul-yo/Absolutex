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
 * The sampling stage is milestone 3's upscaler: mode 0 keeps the single hardware tap, modes 1
 * and 2 evaluate a Mitchell–Netravali or Lanczos-3 kernel (transcribed from [UpscaleMath]) and
 * grade the result in the same pass — colour and upscale never cost two shaders. The CPU picks
 * the mode per draw: kernel only when the draw magnifies and the page is at rest, so gesture
 * frames never pay for taps.
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
    const val UNIFORM_UPSCALER = "upscaler"
    const val UNIFORM_MAP_SCALE = "mapScale"
    const val UNIFORM_MAP_TRANS = "mapTrans"
    const val UNIFORM_CROP_RECT = "cropRect"

    // Upscaler codes. Frozen: they cross from Upscaler.code, so append-only on both sides.
    const val UPSCALER_PLATFORM = 0
    const val UPSCALER_MITCHELL = 1
    const val UPSCALER_LANCZOS = 2

    const val SOURCE = """uniform shader content;
uniform float brightness;
uniform float contrast;
uniform float saturation;
uniform float temperature;
uniform float aggression;
uniform float vibrance;
uniform vec3 gammaExp;
uniform int upscaler;
uniform vec2 mapScale;
uniform vec2 mapTrans;
uniform vec4 cropRect;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float WB_STRENGTH = 0.25;
// Mitchell-Netravali with B = C = 1/3, expanded (see UpscaleMath): (7x^3 - 12x^2 + 16/3) / 6
// under 1, ((-7/3)x^3 + 12x^2 - 20x + 32/3) / 6 under 2.
float mitchell(float x) {
    x = abs(x);
    if (x < 1.0) {
        return (7.0 * x * x * x - 12.0 * x * x + 16.0 / 3.0) / 6.0;
    }
    if (x < 2.0) {
        return ((-7.0 / 3.0) * x * x * x + 12.0 * x * x - 20.0 * x + 32.0 / 3.0) / 6.0;
    }
    return 0.0;
}
float sinc(float x) {
    float pix = 3.14159265 * x;
    return sin(pix) / pix;
}
float lanczos(float x) {
    x = abs(x);
    if (x >= 3.0) return 0.0;
    if (x == 0.0) return 1.0;
    return sinc(x) * sinc(x / 3.0);
}
// One kernel tap set at bitmap-pixel p. The child BitmapShader keeps the forward
// bitmap→canvas matrix (mapScale/mapTrans), so content.eval() takes a canvas-space
// coordinate and the shader inverse-maps it to the bitmap. Each tap is therefore
// a bitmap pixel forward-mapped back to canvas space: eval(tap * mapScale + mapTrans).
// (Evaluating (tap - mapTrans) / mapScale here was the second half of the M3 bug:
// that re-inverse-maps an already-bitmap tap, so every tap lands near the center
// and the kernel collapses.) Edge taps clamp via the shader's CLAMP tiling. The
// accumulator renormalises, matching UpscaleMath.resampleChannel at the borders.
vec3 sampleMitchell(vec2 p) {
    vec2 base = floor(p) - 1.0;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            // Clamp the tap to the crop rect so edge taps don't read beyond the crop.
            vec2 tap = clamp(base + vec2(float(i), float(j)) + 0.5, cropRect.xy, cropRect.zw);
            float w = mitchell(tap.x - p.x) * mitchell(tap.y - p.y);
            acc += content.eval(tap * mapScale + mapTrans).rgb * w;
            wsum += w;
        }
    }
    return acc / wsum;
}
vec3 sampleLanczos(vec2 p) {
    vec2 base = floor(p) - 2.0;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 6; j++) {
        for (int i = 0; i < 6; i++) {
            vec2 tap = clamp(base + vec2(float(i), float(j)) + 0.5, cropRect.xy, cropRect.zw);
            float w = lanczos(tap.x - p.x) * lanczos(tap.y - p.y);
            acc += content.eval(tap * mapScale + mapTrans).rgb * w;
            wsum += w;
        }
    }
    return acc / wsum;
}
vec4 main(vec2 fragCoord) {
    vec4 src = content.eval(fragCoord);
    vec3 c = src.rgb;
    // Grading a kernel sample costs the taps; the platform path keeps the single hardware tap.
    // Branches are on uniforms, so no fragment divergence on either side.
    //
    // The sampling position must be in bitmap space. mapScale/mapTrans are the forward
    // bitmap→canvas placement (contentMatrix), so the canvas fragment coordinate is inverse-
    // mapped: bmp = (fragCoord - mapTrans) / mapScale. (The forward map here was the M3 bug: it
    // collapsed a Mitchell/Lanczos window to a sub-pixel footprint, so the upscaler silently did
    // almost nothing.) The kernel taps are bitmap pixels around that center.
    if (upscaler == 1) {
        vec2 p = (fragCoord - mapTrans) / mapScale;
        c = sampleMitchell(p);
    } else if (upscaler == 2) {
        vec2 p = (fragCoord - mapTrans) / mapScale;
        c = sampleLanczos(p);
    }
    float shift = temperature * aggression * WB_STRENGTH;
    c = c * vec3(1.0 + shift, 1.0, 1.0 - shift);
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
