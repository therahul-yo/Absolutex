package com.absolutex.source

import com.absolutex.model.IssueNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueNumberTest {

    @Test
    fun `keeps the scanner's own spelling alongside the value`() {
        val padded = IssueNumber.parse("001")!!
        assertEquals(1.0, padded.value, 0.0)
        assertEquals("001", padded.raw)
    }

    @Test
    fun `parses a fractional issue`() {
        val half = IssueNumber.parse("10.5")!!
        assertEquals(10.5, half.value, 0.0)
        assertEquals("10.5", half.raw)
    }

    @Test
    fun `parses a letter-suffixed issue off its leading number`() {
        val variant = IssueNumber.parse("1A")!!
        assertEquals(1.0, variant.value, 0.0)
        assertEquals("1A", variant.raw)
    }

    @Test
    fun `trims surrounding whitespace out of the raw form`() {
        assertEquals("12", IssueNumber.parse("  12  ")!!.raw)
    }

    @Test
    fun `rejects anything that does not start with a number`() {
        // toDoubleOrNull would accept all of these; an issue number is not a float literal.
        for (text in listOf("", "   ", "NaN", "Infinity", "-1", "+1", ".5", "abc", "#5", "v2")) {
            assertNull("should have rejected \"" + text + "\"", IssueNumber.parse(text))
        }
    }

    @Test
    fun `formats whole numbers without a decimal tail`() {
        assertEquals("1", IssueNumber.parse("001")!!.format())
        assertEquals("700", IssueNumber.parse("700")!!.format())
        assertEquals("10.5", IssueNumber.parse("10.5")!!.format())
    }

    @Test
    fun `orders by value, not by text`() {
        val sorted = listOf("10", "2", "1", "10.5", "001")
            .mapNotNull { IssueNumber.parse(it) }
            .sorted()
            .map { it.raw }
        assertEquals(listOf("1", "001", "2", "10", "10.5"), sorted)
    }

    @Test
    fun `two spellings of one issue tie in a sort without being equal`() {
        val one = IssueNumber.parse("1")!!
        val padded = IssueNumber.parse("001")!!
        assertEquals("they must tie so a mixed-padding series stays stable", 0, one.compareTo(padded))
        assertNotEquals("but they are not the same record", one, padded)
    }
}
