package com.absolutex.feature.library

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins what is still ours about a file size now that the formatting itself is the platform's.
 *
 * The old tests asserted exact strings — "1.0 KB" for 1024 bytes — against a hand-rolled binary
 * formatter. Those assertions are deliberately gone: `Formatter.formatFileSize` has measured in
 * SI units since Android 8, so the same input now reads "1.02 kB", and re-pinning the platform's
 * exact output would recreate the coupling this change exists to remove. What is asserted here
 * is the rule this module still owns, and the property the switch was made for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatSizeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun inLocale(tag: String, bytes: Long): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return formatSize(context.createConfigurationContext(configuration), bytes)
    }

    @Test
    fun `a size that could not be read is not rendered as a number`() {
        // sizeBytes comes off disk; a stat failure must never print "-1 B". This branch is the
        // only formatting decision left in this module, so it is the one worth pinning exactly.
        assertEquals(context.getString(R.string.library_size_unknown), formatSize(context, -1))
    }

    @Test
    fun `an ordinary size renders as a number with a unit`() {
        val label = formatSize(context, 1536)
        assertTrue("expected digits in $label", label.any { it.isDigit() })
        assertTrue("expected a unit in $label", label.any { it.isLetter() })
    }

    @Test
    fun `zero is a size, not a failure`() {
        assertNotEquals(context.getString(R.string.library_size_unknown), formatSize(context, 0))
    }

    @Test
    fun `the size is rendered in the reader's language`() {
        // The point of delegating: German writes 1,5 where English writes 1.5. Asserted as a
        // difference rather than by pinning either separator — pinning the platform's exact
        // output is precisely the coupling this change removed.
        assertNotEquals(inLocale("en", 1536), inLocale("de", 1536))
    }
}
