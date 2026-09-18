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
    }

    @Test
    fun `shader samples content at the fragment coordinate and preserves alpha`() {
        val src = ColourShader.SOURCE
        assertTrue(src.contains("${ColourShader.UNIFORM_CONTENT}.eval(fragCoord)"))
        assertTrue(src.contains("src.a"))
    }

    @Test
    fun `shader transcribes the reference op order`() {
        // Contrast about mid-grey, additive brightness, luma blend, explicit clamp — the same
        // sentence as ColourMath.adjust. A reorder on either side breaks the transcription.
        val src = ColourShader.SOURCE.replace(" ", "").replace("\n", "")
        assertTrue(src.contains("(src.rgb-0.5)*contrast+0.5+brightness"))
        assertTrue(src.contains("mix(vec3(luma),c,saturation)"))
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
}
