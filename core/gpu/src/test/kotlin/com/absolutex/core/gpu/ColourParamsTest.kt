package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourParamsTest {

    @Test
    fun `defaults are neutral`() {
        assertTrue(ColourParams().isNeutral)
        assertTrue(ColourParams.NEUTRAL.isNeutral)
    }

    @Test
    fun `any field off identity breaks neutrality`() {
        assertFalse(ColourParams(brightness = 0.01f).isNeutral)
        assertFalse(ColourParams(contrast = 1.01f).isNeutral)
        assertFalse(ColourParams(saturation = 0.99f).isNeutral)
        assertFalse(ColourParams(temperature = 0.01f).isNeutral)
        assertFalse(ColourParams(vibrance = 0.01f).isNeutral)
        assertFalse(ColourParams(gamma = 1.01f).isNeutral)
        assertFalse(ColourParams(gammaR = 1.01f).isNeutral)
        assertFalse(ColourParams(gammaG = 1.01f).isNeutral)
        assertFalse(ColourParams(gammaB = 1.01f).isNeutral)
    }

    @Test
    fun `aggression alone is still neutral`() {
        // Aggression only scales a temperature; with none set it does nothing.
        assertTrue(ColourParams(wbAggression = 0f).isNeutral)
        assertTrue(ColourParams(wbAggression = 0.2f).isNeutral)
    }

    @Test
    fun `encode and decode round-trip`() {
        val original = ColourParams(
            brightness = 0.15f,
            contrast = 1.1f,
            saturation = 1.25f,
            temperature = -0.4f,
            wbAggression = 0.8f,
            vibrance = 0.6f,
            gamma = 1.1f,
            gammaR = 0.9f,
            gammaG = 1f,
            gammaB = 1.2f,
        )
        assertEquals(original, ColourParams.decode(original.encode()))
    }

    @Test
    fun `neutral survives an encode round-trip`() {
        assertEquals(ColourParams.NEUTRAL, ColourParams.decode(ColourParams.NEUTRAL.encode()))
    }

    @Test
    fun `absent keys resolve to defaults`() {
        // The PrefCodec convention: a milestone-1 extra still decodes after new fields land.
        assertEquals(
            ColourParams(brightness = 0.15f, contrast = 1.1f, saturation = 1.25f),
            ColourParams.decode("b=0.15,c=1.1,s=1.25"),
        )
    }

    @Test
    fun `clamped pulls every field into its slider range`() {
        val clamped = ColourParams(
            brightness = 5f,
            contrast = -1f,
            saturation = 9f,
            temperature = -9f,
            wbAggression = 2f,
            vibrance = -2f,
            gamma = 0f,
            gammaR = 9f,
            gammaG = -9f,
            gammaB = 9f,
        ).clamped()
        assertEquals(ColourParams.BRIGHTNESS_RANGE.endInclusive, clamped.brightness)
        assertEquals(ColourParams.CONTRAST_RANGE.start, clamped.contrast)
        assertEquals(ColourParams.SATURATION_RANGE.endInclusive, clamped.saturation)
        assertEquals(ColourParams.TEMPERATURE_RANGE.start, clamped.temperature)
        assertEquals(ColourParams.AGGRESSION_RANGE.endInclusive, clamped.wbAggression)
        assertEquals(ColourParams.VIBRANCE_RANGE.start, clamped.vibrance)
        assertEquals(ColourParams.GAMMA_RANGE.start, clamped.gamma)
        assertEquals(ColourParams.GAMMA_CHANNEL_RANGE.endInclusive, clamped.gammaR)
        assertEquals(ColourParams.GAMMA_CHANNEL_RANGE.start, clamped.gammaG)
        assertEquals(ColourParams.GAMMA_CHANNEL_RANGE.endInclusive, clamped.gammaB)
    }

    @Test
    fun `corrupt extras fall back to null, never throw`() {        assertNull(ColourParams.decode(null))
        assertNull(ColourParams.decode(""))
        assertNull(ColourParams.decode("b=0.1,c=1.0,s=1.0,x=2")) // unknown key
        assertNull(ColourParams.decode("b=hot,c=1.0,s=1.0")) // unparseable
        assertNull(ColourParams.decode("b=NaN,c=1.0,s=1.0")) // non-finite
        assertNull(ColourParams.decode("b=0.1;c=1.0;s=1.0")) // wrong separator
        assertNull(ColourParams.decode("b=0.1,b=hot")) // corrupt duplicate wins: rejected
    }
}
