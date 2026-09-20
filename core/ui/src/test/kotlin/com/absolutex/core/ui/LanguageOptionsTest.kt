package com.absolutex.core.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the language picker's list building and selection matching, and the one invariant that
 * spans two files: [SUPPORTED_LOCALES] and `res/xml/locales_config.xml` must agree.
 *
 * Pure JVM on purpose. Everything here is `java.util.Locale` and XML parsing, with no Compose
 * and no Android, so it runs without a device and without the SDK.
 */
class LanguageOptionsTest {

    private val system = "System default"

    private fun labels(vararg tags: String) =
        languageOptions(tags.toList(), system).map { it.label }

    @Test
    fun `system default leads the list and is always present`() {
        assertEquals(listOf(system), labels())
        assertEquals(system, languageOptions(listOf("de"), system).first().label)
        assertTrue(languageOptions(listOf("de"), system).first().isSystemDefault)
    }

    @Test
    fun `languages are named in their own language`() {
        // Someone hunting for their language in a UI they cannot read looks for the word they
        // know, so "Deutsch" rather than "German".
        assertEquals(listOf(system, "English", "Deutsch"), labels("en", "de"))
    }

    @Test
    fun `an endonym the locale writes lowercase is capitalised`() {
        // CLDR gives French "français"; a picker row should read "Français".
        assertEquals(listOf(system, "Français"), labels("fr"))
    }

    @Test
    fun `a non-latin endonym survives intact`() {
        val japanese = labels("ja").last()
        assertTrue("expected Japanese in Japanese, got $japanese", japanese.isNotEmpty())
        assertTrue("endonym must not be the tag", japanese != "ja")
    }

    @Test
    fun `blank and malformed tags are dropped`() {
        assertEquals(listOf(system), labels("", "   ", "!!!"))
    }

    @Test
    fun `a tag the jvm cannot name is dropped rather than shown raw`() {
        // Offering a row reading "qqq" tells the user nothing; a typo should read as a missing
        // language, not a broken one.
        assertEquals(listOf(system), labels("qqq"))
    }

    @Test
    fun `duplicates keep their first position`() {
        assertEquals(listOf(system, "English", "Deutsch"), labels("en", "de", "en"))
    }

    @Test
    fun `order follows the configured order, not an alphabetical sort`() {
        // Collating endonyms across scripts has no single right answer, so the list is curated.
        assertEquals(listOf(system, "Deutsch", "English"), labels("de", "en"))
    }

    @Test
    fun `nothing applied means the system default row`() {
        assertNull(selectedTag(emptyList(), languageOptions(listOf("en", "de"), system)))
        assertNull(selectedTag(listOf("  "), languageOptions(listOf("en", "de"), system)))
    }

    @Test
    fun `an applied tag matches its row exactly`() {
        assertEquals("de", selectedTag(listOf("de"), languageOptions(listOf("en", "de"), system)))
    }

    @Test
    fun `a region-qualified tag matches a language-only row`() {
        // The system can hand back "pt-BR" for a list offering plain "pt". Leaving that
        // unmatched would show "System default" to someone plainly reading Portuguese.
        assertEquals("pt", selectedTag(listOf("pt-BR"), languageOptions(listOf("en", "pt"), system)))
    }

    @Test
    fun `an exact regional tag beats another region of the same language`() {
        // A list offering both pt-PT and pt-BR must select the one actually applied. Matching on
        // language alone would hand back whichever comes first, silently giving a Brazilian
        // reader European Portuguese — and both are on the milestone 5 list.
        val options = languageOptions(listOf("pt-PT", "pt-BR"), system)
        assertEquals("pt-BR", selectedTag(listOf("pt-BR"), options))
        assertEquals("pt-PT", selectedTag(listOf("pt-PT"), options))
    }

    @Test
    fun `an applied tag nothing offers falls back to the system default`() {
        assertNull(selectedTag(listOf("is"), languageOptions(listOf("en", "de"), system)))
    }

    @Test
    fun `supported locales and locales_config agree`() {
        // The mistake this catches: a translation added without its config row is invisible to
        // the system picker, and a config row without a translation offers a language that then
        // renders in English.
        val candidates = listOf(
            File("src/main/res/xml/locales_config.xml"),
            File("core/ui/src/main/res/xml/locales_config.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("locales_config.xml not found; looked in ${candidates.map { it.absolutePath }}")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.getElementsByTagName("locale")
        val configured = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name")
        }
        assertEquals(SUPPORTED_LOCALES, configured)
    }
}
