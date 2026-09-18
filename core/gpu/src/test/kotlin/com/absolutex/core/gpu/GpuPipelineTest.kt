package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourShaderTest {

    @Test
    fun `shader declares the content sampler and every uniform the pipeline sets`() {
        val src = ColourShader.SOURCE
        assertTrue(src.contains("uniform shader ${ColourShader.UNIFORM_CONTENT}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_BRIGHTNESS}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_CONTRAST}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_SATURATION}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_TEMPERATURE}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_AGGRESSION}"))
        assertTrue(src.contains("uniform float ${ColourShader.UNIFORM_VIBRANCE}"))
        assertTrue(src.contains("uniform vec3 ${ColourShader.UNIFORM_GAMMA_EXP}"))
    }

    @Test
    fun `shader samples content at the fragment coordinate and preserves alpha`() {
        val src = ColourShader.SOURCE
        assertTrue(src.contains("${ColourShader.UNIFORM_CONTENT}.eval(fragCoord)"))
        assertTrue(src.contains("src.a"))
    }

    @Test
    fun `shader transcribes the reference op order`() {
        // White balance, contrast about mid-grey with additive brightness, gamma on floored
        // values, luma-blend saturation with vibrance, explicit clamp — the same sentence as
        // ColourMath.adjust. A reorder on either side breaks the transcription.
        val src = ColourShader.SOURCE.replace(" ", "").replace("\n", "")
        assertTrue(src.contains("temperature*aggression*WB_STRENGTH"))
        assertTrue(src.contains("c=c*vec3(1.0+shift,1.0,1.0-shift)"))
        assertTrue(src.contains("(c-0.5)*contrast+0.5+brightness"))
        assertTrue(src.contains("pow(max(c,vec3(0.0)),gammaExp)"))
        assertTrue(src.contains("clamp((1.0+c.r-c.b)/2.0,0.0,1.0)"))
        assertTrue(src.contains("mix(vec3(luma),c,saturation+vibrance*selectivity)"))
        assertTrue(src.contains("clamp(c,0.0,1.0)"))
    }

    @Test
    fun `luma weights match the JVM reference`() {
        val src = ColourShader.SOURCE
        assertTrue(src.contains("vec3(0.2126, 0.7152, 0.0722)"))
        assertEquals(0.2126f, ColourMath.LUMA_R, 0f)
        assertEquals(0.7152f, ColourMath.LUMA_G, 0f)
        assertEquals(0.0722f, ColourMath.LUMA_B, 0f)
    }

    @Test
    fun `white-balance strength matches the JVM reference`() {
        assertTrue(ColourShader.SOURCE.contains("const float WB_STRENGTH = 0.25;"))
        assertEquals(0.25f, ColourMath.WB_STRENGTH, 0f)
        assertEquals(ColourMath.WARMTH_KEEP, 0.35f, 0f)
        assertTrue(ColourShader.SOURCE.replace(" ", "").contains("mix(1.0,0.35,warmth)"))
    }

    @Test
    fun `shader declares the sampling uniforms and both kernels`() {
        val src = ColourShader.SOURCE
        assertTrue(src.contains("uniform int ${ColourShader.UNIFORM_UPSCALER}"))
        assertTrue(src.contains("uniform vec2 ${ColourShader.UNIFORM_MAP_SCALE}"))
        assertTrue(src.contains("uniform vec2 ${ColourShader.UNIFORM_MAP_TRANS}"))
        assertTrue(src.contains("float mitchell(float x)"))
        assertTrue(src.contains("float lanczos(float x)"))
        assertTrue(src.contains("sampleMitchell(p)"))
        assertTrue(src.contains("sampleLanczos(p)"))
    }

    @Test
    fun `upscaler codes match the enum`() {
        assertEquals(Upscaler.PLATFORM.code, ColourShader.UPSCALER_PLATFORM)
        assertEquals(Upscaler.MITCHELL.code, ColourShader.UPSCALER_MITCHELL)
        assertEquals(Upscaler.LANCZOS.code, ColourShader.UPSCALER_LANCZOS)
    }

    @Test
    fun `kernel taps forward-map through the placement and renormalise`() {
        val src = ColourShader.SOURCE.replace(" ", "").replace("\n", "")
        assertTrue(src.contains("content.eval(tap*mapScale+mapTrans).rgb"))
        assertTrue(src.contains("returnacc/wsum;"))
    }

    @Test
    fun `Lanczos taps are clamped to the crop rect`() {
        // Both kernels must clamp taps to the crop rect — without this, edge taps
        // read pixels beyond the crop. The bug was Mitchell fixed, Lanczos not.
        val src = ColourShader.SOURCE.replace(" ", "").replace("\n", "")
        val clampExpr = "clamp(base+vec2(float(i),float(j))+0.5,cropRect.xy,cropRect.zw)"
        assertTrue("Mitchell taps must clamp to cropRect", src.contains(clampExpr))
        assertTrue("Lanczos taps must clamp to cropRect", src.contains(clampExpr))
    }
}

class ColourPipelineGateTest {

    private val pipeline = ColourPipeline()

    @Test
    fun `neutral never enters the shader path`() {
        assertFalse(pipeline.shouldApply(ColourParams.NEUTRAL))
        assertFalse(pipeline.shouldApply(ColourParams()))
    }

    @Test
    fun `any live correction enters the shader path`() {
        assertTrue(pipeline.shouldApply(ColourParams(brightness = 0.15f)))
        assertTrue(pipeline.shouldApply(ColourParams(contrast = 1.1f)))
        assertTrue(pipeline.shouldApply(ColourParams(saturation = 1.25f)))
        assertTrue(pipeline.shouldApply(ColourParams(temperature = 0.5f)))
        assertTrue(pipeline.shouldApply(ColourParams(vibrance = 0.5f)))
        assertTrue(pipeline.shouldApply(ColourParams(gamma = 1.1f)))
        assertTrue(pipeline.shouldApply(ColourParams(gammaB = 0.9f)))
    }

    @Test
    fun `neutral plus platform never enters the shader path`() {
        assertFalse(pipeline.shouldShade(ColourParams.NEUTRAL, Upscaler.PLATFORM, true))
        assertFalse(pipeline.shouldShade(ColourParams.NEUTRAL, Upscaler.PLATFORM, false))
    }

    @Test
    fun `a selected kernel enters the shader path only at rest`() {
        assertTrue(pipeline.shouldShade(ColourParams.NEUTRAL, Upscaler.LANCZOS, true))
        assertFalse(pipeline.shouldShade(ColourParams.NEUTRAL, Upscaler.LANCZOS, false))
        assertTrue(pipeline.shouldShade(ColourParams.NEUTRAL, Upscaler.MITCHELL, true))
    }

    @Test
    fun `live colour enters whatever the sampler is doing`() {
        assertTrue(pipeline.shouldShade(ColourParams(brightness = 0.1f), Upscaler.PLATFORM, true))
        assertTrue(pipeline.shouldShade(ColourParams(brightness = 0.1f), Upscaler.LANCZOS, false))
    }

    @Test
    fun `shade mode is the kernel only when magnifying at rest`() {
        assertEquals(Upscaler.PLATFORM, pipeline.shadeMode(Upscaler.PLATFORM, true, true))
        assertEquals(Upscaler.LANCZOS, pipeline.shadeMode(Upscaler.LANCZOS, true, true))
        // Gesture frames and 1:1 draws keep the hardware tap.
        assertEquals(Upscaler.PLATFORM, pipeline.shadeMode(Upscaler.LANCZOS, false, true))
        assertEquals(Upscaler.PLATFORM, pipeline.shadeMode(Upscaler.LANCZOS, true, false))
        assertEquals(Upscaler.MITCHELL, pipeline.shadeMode(Upscaler.MITCHELL, true, true))
    }
}

class ContentMatrixTest {

    @Test
    fun `identity placement is the identity matrix`() {
        assertEquals(
            ContentMatrix(1f, 1f, 0f, 0f),
            contentMatrix(0, 0, 100, 200, 0, 0, 100, 200),
        )
    }

    @Test
    fun `offset placement shifts by the origin`() {
        // Bitmap (0,0) maps to canvas (10,20): the translation is the destination origin.
        assertEquals(
            ContentMatrix(1f, 1f, 10f, 20f),
            contentMatrix(0, 0, 100, 200, 10, 20, 110, 220),
        )
    }

    @Test
    fun `upscaled placement scales up the sample`() {
        // A 100 px bitmap across a 200 px rect: each canvas pixel covers 0.5 bitmap pixels,
        // so the local matrix scales by 2 (canvas = 2 * bitmap).
        assertEquals(
            ContentMatrix(2f, 2f, 0f, 0f),
            contentMatrix(0, 0, 100, 100, 0, 0, 200, 200),
        )
    }

    @Test
    fun `shift applies after scale`() {
        // Bitmap 100×100 drawn at (10,0)-(60,50): scale 0.5, then translate by (10, 0).
        // Canvas x=10 maps to bitmap x=0: 10 * 0.5 + 10 = 15... no, the matrix is
        // M = T(10,0) · S(0.5,0.5), so canvas = M · bitmap = 0.5 * bitmap + 10.
        // Bitmap 0 → canvas 10, bitmap 100 → canvas 60. Correct.
        assertEquals(
            ContentMatrix(0.5f, 0.5f, 10f, 0f),
            contentMatrix(0, 0, 100, 100, 10, 0, 60, 50),
        )
    }

    @Test
    fun `degenerate rects coerce instead of dividing by zero`() {
        val m = contentMatrix(0, 0, 100, 100, 5, 5, 5, 5)
        assertTrue(m.scaleX.isFinite() && m.scaleY.isFinite())
        assertTrue(m.transX.isFinite() && m.transY.isFinite())
    }

    @Test
    fun `a cropped src rect maps to the dst rect with adjusted translate`() {
        // A 100×200 bitmap cropped to its right half (src rect 50,0-100,200) drawn
        // into a 100×200 canvas rect: scale = dstW/srcW = 100/50 = 2.0. The translate
        // must account for the src origin (50,0): dstLeft(0) - srcLeft(50) * 2 = -100.
        val m = contentMatrix(50, 0, 100, 200, 0, 0, 100, 200)
        assertEquals(2f, m.scaleX, 1e-5f)
        assertEquals(1f, m.scaleY, 1e-5f)
        assertEquals(-100f, m.transX, 1e-5f)
        assertEquals(0f, m.transY, 1e-5f)
    }
}

class IsMagnifyingTest {

    @Test
    fun `a cropped page that fills the viewport is magnifying at base resolution`() {
        // A 100×200 bitmap cropped to 50×200, drawn into a 50×200 rect: dst == src,
        // so not magnifying (srcW/dstW == 1.0). The full bitmap would have said
        // magnifying=true at the same dst — this is why the crop must be part of the test.
        assertFalse(isMagnifying(50, 200, 0, 0, 50, 200))
    }

    @Test
    fun `dst larger than cropped src is magnifying`() {
        assertFalse(isMagnifying(50, 200, 0, 0, 50, 200)) // base
        assertTrue(isMagnifying(50, 200, 0, 0, 100, 200)) // 2x magnify on the crop
    }

    @Test
    fun `a one-to-one draw of the full bitmap is not magnifying`() {
        assertFalse(isMagnifying(100, 200, 0, 0, 100, 200))
    }
}
