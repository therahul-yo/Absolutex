package com.absolutex.core.ui

import java.util.Locale

/**
 * The BCP-47 tags whose UI text this app actually ships.
 *
 * This is the source of truth and `res/xml/locales_config.xml` mirrors it; `LanguageOptionsTest`
 * fails if the two drift, which is the mistake this pairing exists to catch — a translation
 * added without its config row is invisible to the system picker, and a config row without a
 * translation offers a language that silently renders in English.
 *
 * A tag belongs here only once `values-<tag>/` exists and its review status is recorded. Adding
 * one early is worse than adding it late: the user picks their language and nothing changes.
 */
val SUPPORTED_LOCALES: List<String> = listOf("en")

/**
 * One row of the language picker.
 *
 * [tag] is null for "follow the system", which is the default and is never absent from the list.
 */
data class LanguageOption(val tag: String?, val label: String) {
    val isSystemDefault: Boolean get() = tag == null
}

/**
 * The picker's rows: system default first, then one per usable tag in [tags] order.
 *
 * Each language is labelled in **its own** language — "Deutsch", not "German" — because someone
 * hunting for their language in a UI they cannot currently read is looking for the word they
 * know. Order is [tags]' order rather than a sort: collating endonyms across Latin, Arabic, Han
 * and Devanagari has no single right answer, so the list is curated instead of pretending.
 *
 * Tags that are blank, malformed, or that the JVM cannot name are dropped rather than shown, so
 * a typo in the config surfaces as a missing row instead of one reading "qqq". Duplicates keep
 * their first position.
 */
fun languageOptions(
    tags: List<String> = SUPPORTED_LOCALES,
    systemDefaultLabel: String,
): List<LanguageOption> =
    listOf(LanguageOption(null, systemDefaultLabel)) +
        tags.map(String::trim).filter(String::isNotEmpty).distinct().mapNotNull(::optionFor)

/**
 * The row for one tag, or null when it names no language this JVM can write.
 *
 * The JVM hands back the tag itself when it has no name for the locale, so a typo in the config
 * surfaces as a missing row rather than one reading "qqq" — which would tell a reader nothing.
 */
private fun optionFor(tag: String): LanguageOption? {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language.isEmpty()) return null
    val endonym = locale.getDisplayName(locale)
    if (endonym.isEmpty() || endonym.equals(tag, ignoreCase = true)) return null
    return LanguageOption(tag, endonym.replaceFirstChar { it.titlecase(locale) })
}

/**
 * Which row [options] should show as selected, given what `LocaleManager` reports applied.
 *
 * An empty [appliedTags] means the app follows the system, so nothing is pinned and the result
 * is null. Otherwise the applied tag is matched exactly, then by language alone: the system can
 * hand back a region-qualified "pt-BR" for a list that offers plain "pt", and leaving that
 * unmatched would show the user "System default" while they are plainly reading Portuguese.
 * A tag matching nothing on offer also falls back to null rather than inventing a row.
 */
fun selectedTag(appliedTags: List<String>, options: List<LanguageOption>): String? {
    val applied = appliedTags.firstOrNull()?.trim().orEmpty()
    if (applied.isEmpty()) return null
    val offered = options.mapNotNull { it.tag }
    val language = Locale.forLanguageTag(applied).language
    val exact = offered.firstOrNull { it.equals(applied, ignoreCase = true) }
    // Stated once, in priority order: the tag itself, then the same language whatever its
    // region, then nothing rather than a row the reader did not choose.
    return when {
        exact != null -> exact
        language.isEmpty() -> null
        else -> offered.firstOrNull { Locale.forLanguageTag(it).language == language }
    }
}
