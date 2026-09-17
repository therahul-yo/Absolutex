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
        assertTrue(src.contains("src.rgb*vec3(1.0+shift,1.0,1.0-shift)"))
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

    @Test(expected = IllegalArgumentException::class)
    fun `neutral is rejected at the paint seam, not drawn through an identity shader`() {
        // The guard paintFor enforces, reached here without a Bitmap (unconstructable on the JVM).
        pipeline.checkApplicable(ColourParams.NEUTRAL)
    }
}

class ContentMatrixTest {

    @Test
    fun `identity placement is the identity matrix`() {
        assertEquals(
            ContentMatrix(1f, 1f, 0f, 0f),
            contentMatrix(100, 200, 0, 0, 100, 200),
        )
    }

    @Test
    fun `offset placement shifts by the origin`() {
        // Bitmap (0,0) maps to canvas (10,20): the translation is the destination origin.
        assertEquals(
            ContentMatrix(1f, 1f, 10f, 20f),
            contentMatrix(100, 200, 10, 20, 110, 220),
        )
    }

    @Test
    fun `upscaled placement scales up the sample`() {
        // A 100 px bitmap across a 200 px rect: each canvas pixel covers 0.5 bitmap pixels,
        // so the local matrix scales by 2 (canvas = 2 * bitmap).
        assertEquals(
            ContentMatrix(2f, 2f, 0f, 0f),
            contentMatrix(100, 100, 0, 0, 200, 200),
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
            contentMatrix(100, 100, 10, 0, 60, 50),
        )
    }

    @Test
    fun `degenerate rects coerce instead of dividing by zero`() {
        val m = contentMatrix(100, 100, 5, 5, 5, 5)
        assertTrue(m.scaleX.isFinite() && m.scaleY.isFinite())
        assertTrue(m.transX.isFinite() && m.transY.isFinite())
    }
}
