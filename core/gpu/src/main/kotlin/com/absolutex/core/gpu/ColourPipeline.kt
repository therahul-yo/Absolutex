package com.absolutex.core.gpu

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * Owns the draw-time colour objects for one page (§4, milestone 1).
 *
 * One [RuntimeShader] and one [Paint] are hoisted here and mutated in place: uniforms are
 * rewritten per frame and the per-bitmap [BitmapShader] swapped per draw, so the corrected path
 * allocates nothing per frame, per tile or per draw call after a bitmap's first sight. The
 * content cache is an access-ordered LRU capped at [MAX_CONTENT_SHADERS]: evicted tiles stop
 * being touched and fall out, so this never pins TileCache evictions in memory beyond the cap.
 * ([TileCache] deliberately does not recycle on eviction, so an isRecycled sweep would not work;
 * the cap is the whole reclamation story.)
 *
 * The constructor touches no Android classes — every graphics object is created on first
 * non-neutral use — so the neutral gate below stays unit-testable on the JVM.
 */
class ColourPipeline {

    private var runtime: RuntimeShader? = null
    private var paint: Paint? = null
    private var matrix: Matrix? = null
    private var lastParams: ColourParams? = null

    private val contents = object : LinkedHashMap<Bitmap, BitmapShader>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Bitmap, BitmapShader>): Boolean =
            size > MAX_CONTENT_SHADERS
    }

    /**
     * The neutral gate. False means the draw path must be bit-for-bit today's: the caller draws
     * with its plain Paint, no layer, no shader — correction off costs nothing by construction,
     * not by running an identity shader.
     */
    fun shouldApply(params: ColourParams): Boolean = !params.isNeutral

    /**
     * Enforces the neutral gate at the paint seam: neutral params must use the plain draw path.
     * A separate pure function so the guard itself is unit-tested on the JVM (a Bitmap cannot
     * be constructed there, so paintFor's own require is unreachable from a JVM test).
     */
    fun checkApplicable(params: ColourParams) {
        require(!params.isNeutral) { "neutral params must use the plain draw path, not the shader" }
    }

    /**
     * A Paint drawing [bitmap] in full into ([left], [top], [right], [bottom]) through the colour
     * shader. Requires non-neutral [params] — see [shouldApply].
     */
    fun paintFor(
        params: ColourParams,
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Paint {
        checkApplicable(params)
        val rt = runtime ?: RuntimeShader(ColourShader.SOURCE).also {
            runtime = it
            lastParams = null
        }
        // Uniform writes are CPU-side until draw; still, skip them when nothing changed so a
        // static page costs one input swap per tile and nothing else.
        if (params != lastParams) {
            rt.setFloatUniform(ColourShader.UNIFORM_BRIGHTNESS, params.brightness)
            rt.setFloatUniform(ColourShader.UNIFORM_CONTRAST, params.contrast)
            rt.setFloatUniform(ColourShader.UNIFORM_SATURATION, params.saturation)
            lastParams = params
        }
        var content = contents[bitmap]
        if (content == null) {
            // Attached BitmapShaders default to NEAREST sampling (BitmapShader docs), which would
            // pixelate every scaled draw; LINEAR is API 33, the same floor as RuntimeShader itself.
            content = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
            contents[bitmap] = content
        }
        val placement = contentMatrix(bitmap.width, bitmap.height, left, top, right, bottom)
        val m = matrix ?: Matrix().also { matrix = it }
        // preTranslate, not post: the sample is scale-then-shift, M = T * S, so the shift
        // applies unscaled after the scale.
        m.setScale(placement.scaleX, placement.scaleY)
        m.preTranslate(placement.transX, placement.transY)
        content.setLocalMatrix(m)
        rt.setInputShader(ColourShader.UNIFORM_CONTENT, content)
        return (paint ?: Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).also { paint = it })
            .apply { shader = rt }
    }

    companion object {
        /**
         * Content-shader entries held per page. A 1240×2772 viewport shows at most ~18 512 px
         * tiles per sample level; 64 covers the visible set across levels plus the base layer
         * with room to spare, and anything beyond it re-creates for one small alloc off budget.
         */
        const val MAX_CONTENT_SHADERS = 64
    }
}
