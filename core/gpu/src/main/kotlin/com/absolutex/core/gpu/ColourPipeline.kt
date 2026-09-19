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
 * The cap is the visible working set, not an arbitrary headroom number: a 1240×2772 viewport
 * shows at most ~18 512 px tiles per sample level, and one [ColourPipeline] is created per page
 * composable. With `beyondViewportPageCount = 1` the reader composes three pages, so a 64-entry
 * cap per pipeline would hold up to 192 strongly-referenced bitmaps — each ~1 MB at 512 px
 * ARGB — after [TileCache] has already evicted them, which is several times §3's whole reader
 * budget. 24 entries covers the visible set across sample levels plus the base layer with room
 * to spare, and anything beyond it re-creates for one small alloc off budget.
 *
 * The constructor touches no Android classes — every graphics object is created on first
 * non-neutral use — so the neutral gate below stays unit-testable on the JVM.
 */
class ColourPipeline {

    private var runtime: RuntimeShader? = null
    private var paint: Paint? = null
    private var matrix: Matrix? = null
    private var lastParams: ColourParams? = null
    private var lastMode: Upscaler? = null

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
     * Whether the kernel upscaler may run at all, before any per-draw detail: a kernel is
     * selected and the page is at rest. Magnification is decided per draw in [shadeMode], so
     * gesture frames and 1:1 draws keep the hardware tap whatever is selected.
     */
    fun shouldUpscale(upscaler: Upscaler, atRest: Boolean): Boolean =
        upscaler != Upscaler.PLATFORM && atRest

    /**
     * The draw-branch gate: enter the shader path when colour grading is live or a kernel may
     * run. Pure, so the branch the draw lambda takes is unit-tested on the JVM.
     */
    fun shouldShade(params: ColourParams, upscaler: Upscaler, atRest: Boolean): Boolean =
        shouldApply(params) || shouldUpscale(upscaler, atRest)

    /**
     * The sampling mode for one draw: the selected kernel, or PLATFORM whenever the draw does
     * not magnify — a 1:1 draw has nothing to reconstruct, kernel or not. Pure and tested.
     */
    fun shadeMode(upscaler: Upscaler, atRest: Boolean, magnifying: Boolean): Upscaler =
        if (upscaler != Upscaler.PLATFORM && atRest && magnifying) upscaler else Upscaler.PLATFORM

    /**
     * A Paint drawing [bitmap] in full into ([left], [top], [right], [bottom]) through the
     * shader, or null when the draw needs neither grading nor a kernel — the caller then draws
     * with its plain Paint. Null keeps the M1 promise for upscaler users too: neutral colour
     * with a kernel selected still costs nothing on draws that do not magnify.
     *
     * [upscaler] selects the sampling kernel, but the kernel runs only when this draw magnifies
     * ([isMagnifying]) and the page is at rest ([atRest]): gesture frames keep the hardware tap
     * whatever is selected, so a heavy kernel can never land on the gesture path. Colour
     * uniforms update only when [params] change; the sampling uniforms ride every draw, because
     * the dst-to-bitmap map differs per tile.
     */
    fun paintFor(
        params: ColourParams,
        bitmap: Bitmap,
        srcLeft: Int,
        srcTop: Int,
        srcRight: Int,
        srcBottom: Int,
        dstLeft: Int,
        dstTop: Int,
        dstRight: Int,
        dstBottom: Int,
        upscaler: Upscaler,
        atRest: Boolean,
    ): Paint? {
        val srcW = srcRight - srcLeft
        val srcH = srcBottom - srcTop
        val magnifying = isMagnifying(srcW, srcH, dstLeft, dstTop, dstRight, dstBottom)
        val mode = shadeMode(upscaler, atRest, magnifying)
        if (params.isNeutral && mode == Upscaler.PLATFORM) return null
        val rt = runtime ?: RuntimeShader(ColourShader.SOURCE).also {
            runtime = it
            lastParams = null
            lastMode = null
        }
        if (params != lastParams) {
            syncColourUniforms(rt, params)
            lastParams = params
        }
        val content = contentFor(bitmap)
        val placement = contentMatrix(
            srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom,
        )
        rt.setFloatUniform(
            ColourShader.UNIFORM_CROP_RECT,
            srcLeft.toFloat(), srcTop.toFloat(), srcRight.toFloat(), srcBottom.toFloat(),
        )
        val m = matrix ?: Matrix().also { matrix = it }
        m.setScale(placement.scaleX, placement.scaleY)
        m.postTranslate(placement.transX, placement.transY)
        content.setLocalMatrix(m)
        rt.setInputShader(ColourShader.UNIFORM_CONTENT, content)
        if (mode != lastMode) {
            rt.setIntUniform(ColourShader.UNIFORM_UPSCALER, mode.code)
            lastMode = mode
        }
        rt.setFloatUniform(ColourShader.UNIFORM_MAP_SCALE, placement.scaleX, placement.scaleY)
        rt.setFloatUniform(ColourShader.UNIFORM_MAP_TRANS, placement.transX, placement.transY)
        return (paint ?: Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).also { paint = it })
            .apply { shader = rt }
    }

    /** Colour uniforms, factored out of paintFor: one call site, no per-draw branching. */
    private fun syncColourUniforms(rt: RuntimeShader, params: ColourParams) {
        rt.setFloatUniform(ColourShader.UNIFORM_BRIGHTNESS, params.brightness)
        rt.setFloatUniform(ColourShader.UNIFORM_CONTRAST, params.contrast)
        rt.setFloatUniform(ColourShader.UNIFORM_SATURATION, params.saturation)
        rt.setFloatUniform(ColourShader.UNIFORM_TEMPERATURE, params.temperature)
        rt.setFloatUniform(ColourShader.UNIFORM_AGGRESSION, params.wbAggression)
        rt.setFloatUniform(ColourShader.UNIFORM_VIBRANCE, params.vibrance)
        // Exponents inlined, not via foldedGamma's array: params change on every slider frame
        // while dragging, and that array would be a per-frame allocation in the draw scope.
        rt.setFloatUniform(
            ColourShader.UNIFORM_GAMMA_EXP,
            params.gamma * params.gammaR,
            params.gamma * params.gammaG,
            params.gamma * params.gammaB,
        )
    }

    /** One LINEAR-filtered BitmapShader per bitmap, capped by the access-ordered LRU above. */
    private fun contentFor(bitmap: Bitmap): BitmapShader {
        val cached = contents[bitmap]
        if (cached != null) return cached
        // Attached BitmapShaders default to NEAREST sampling (BitmapShader docs), which would
        // pixelate every scaled draw; LINEAR is API 33, the same floor as RuntimeShader itself.
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
            .also { contents[bitmap] = it }
    }

    companion object {
        /**
         * Content-shader entries held per page. A 1240×2772 viewport shows at most ~18 512 px
         * tiles per sample level; 24 covers the visible set across levels plus the base layer
         * with room to spare, and anything beyond it re-creates for one small alloc off budget.
         * Capped at the visible working set because one pipeline per page composable × three
         * composed pages would otherwise hold 192 strongly-referenced bitmaps after TileCache
         * evicted them — see the class KDoc.
         */
        const val MAX_CONTENT_SHADERS = 24
    }
}
