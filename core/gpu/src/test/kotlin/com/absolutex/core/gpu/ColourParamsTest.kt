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
    }

    @Test
    fun `encode and decode round-trip`() {
        val original = ColourParams(brightness = 0.15f, contrast = 1.1f, saturation = 1.25f)
        assertEquals(original, ColourParams.decode(original.encode()))
    }

    @Test
    fun `neutral survives an encode round-trip`() {
        assertEquals(ColourParams.NEUTRAL, ColourParams.decode(ColourParams.NEUTRAL.encode()))
    }

    @Test
    fun `corrupt extras fall back to null, never throw`() {
        assertNull(ColourParams.decode(null))
        assertNull(ColourParams.decode(""))
        assertNull(ColourParams.decode("b=0.1,c=1.0")) // missing s
        assertNull(ColourParams.decode("b=0.1,c=1.0,s=1.0,extra=2")) // unknown key
        assertNull(ColourParams.decode("b=hot,c=1.0,s=1.0")) // unparseable
        assertNull(ColourParams.decode("b=NaN,c=1.0,s=1.0")) // non-finite
        assertNull(ColourParams.decode("b=0.1;c=1.0;s=1.0")) // wrong separator
    }
}
