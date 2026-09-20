package com.absolutex.core.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the TalkBack label for one colour-grade slider (§7).
 *
 * The label was built in Kotlin as `"$name, $valueText"`, which no translation can reach: the
 * separator is baked into the code. It is now [R.string.gpu_grade_row_desc] with two
 * positional arguments, and these pin that contract — the resource exists, it carries both
 * arguments, and English orders them name-then-value.
 *
 * The separator is the point. In a locale whose decimal mark is a comma, "Brightness, 0,50"
 * is ambiguous and the translation should use a colon instead; that choice is only available
 * once the join lives in the resource.
 *
 * What these cannot see is the call site: reverting [GradeRow] to concatenation would leave
 * them passing. That regression is caught by `tools/check-strings.py`, which reports a
 * hardcoded `contentDescription` in this module and no longer carries one in its baseline.
 *
 * Context comes from Robolectric's own [RuntimeEnvironment] rather than `ApplicationProvider`
 * so this module needs no `androidx.test.core` dependency to run it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradeRowDescriptionTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `describes a slider by name and value`() {
        assertEquals(
            "Brightness, 0.50",
            context.getString(R.string.gpu_grade_row_desc, "Brightness", "0.50"),
        )
    }

    @Test
    fun `carries both positional arguments`() {
        // A translation that drops one silently truncates what TalkBack reads. Fetching the
        // resource unformatted returns the placeholders themselves.
        val raw = context.getString(R.string.gpu_grade_row_desc)
        assertTrue("no %1\$s in: $raw", raw.contains("%1\$s"))
        assertTrue("no %2\$s in: $raw", raw.contains("%2\$s"))
    }

    @Test
    fun `no string in this module is longer than the one the row was sized for`() {
        // The grade rows give their label a fixed fraction of the row, chosen so the longest
        // label fits on one line at ordinary font scale. Nothing enforces that at runtime, and a
        // longer string added later would wrap early and quietly unbalance every row — a change
        // nobody would connect to the layout. So: fail here, where the cause is obvious.
        //
        // Read by reflection over R.string rather than a hand-listed set, because a list would
        // not contain the new string that is exactly the problem.
        //
        // This depends on android.nonTransitiveRClass=true in gradle.properties, which keeps
        // this R to this module's own strings. If that flag is ever turned off, R.string gains
        // every dependency's strings and this starts asserting over Material's — failing for a
        // reason that looks nothing like its name. Check the flag before believing the failure.
        val strings = R.string::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.name to context.getString(it.getInt(null)) }
        val longest = strings.maxByOrNull { it.value.length }
        assertEquals(
            "a longer string than \"White-balance strength\" now exists; re-check the grade row " +
                "label width before widening it further: $strings",
            "gpu_aggression",
            longest?.key,
        )
    }

    @Test
    fun `english orders the name before the value`() {
        val label = context.getString(R.string.gpu_grade_row_desc, "Gamma", "2.20")
        assertTrue(
            "the value should follow the control name in English: $label",
            label.indexOf("Gamma") < label.indexOf("2.20"),
        )
    }
}
