package com.absolutex.source

import com.absolutex.model.ComicInfo
import com.absolutex.model.ComicInfoPage
import com.absolutex.model.PageType
import com.absolutex.model.ReadingFlow

/** The entry name every ComicInfo-aware reader looks for, at the archive root (§2). */
const val COMIC_INFO_ENTRY = "ComicInfo.xml"

/**
 * Serialises [info] as ComicInfo.xml (§2, §6 M5).
 *
 * The contract is **round-trip fidelity against [ComicInfoParser]**, not prettiness: anything this
 * writes, that parser must read back equal. The tests assert exactly that rather than comparing
 * against a golden string, because a golden string only proves the writer still does what it did.
 *
 * Absent fields are omitted rather than written empty. The model's whole point is that null means
 * "the file did not say", and an empty `<Series/>` would come back as null anyway while making the
 * document claim it had an opinion.
 *
 * Built by hand rather than through a `Transformer`: its output varies with the JDK's
 * configuration, and an export that differs between devices would undo the byte-identical
 * guarantee [writeCbz] is careful to give.
 */
fun writeComicInfoXml(info: ComicInfo): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append("<ComicInfo>").append('\n')
    element("Series", info.series)
    // The raw spelling, not format(): the parser keeps what it read, so "1A" and "001" only
    // survive the round trip if the raw text is what goes back out.
    element("Number", info.number?.raw)
    element("Volume", info.volume?.toString())
    element("Title", info.title)
    // One element per name rather than one comma-joined element. The parser splits on commas
    // either way, so a name that *contains* a comma ("Smith, John") cannot survive ComicInfo at
    // all — that is the format's limit, and the tests pin it rather than hide it.
    info.writers.forEach { element("Writer", it) }
    element("Year", info.year?.toString())
    element("PageCount", info.pageCount?.toString())
    element("Manga", info.readingFlow?.toMangaValue())
    if (info.pages.isNotEmpty()) {
        append("  <Pages>").append('\n')
        info.pages.forEach { appendPage(it) }
        append("  </Pages>").append('\n')
    }
    append("</ComicInfo>").append('\n')
}

/**
 * ComicInfo has no vertical reading direction, so [ReadingFlow.VERTICAL] writes nothing at all.
 *
 * Writing "No" would assert left-to-right, which is a different book; omitting `<Manga>` says "the
 * file does not know", which is true. A vertical book therefore does not round-trip through
 * ComicInfo — a limit of the format, pinned by a test so it stays a known loss rather than a bug
 * someone rediscovers.
 */
private fun ReadingFlow.toMangaValue(): String? = when (this) {
    ReadingFlow.RTL -> "YesAndRightToLeft"
    ReadingFlow.LTR -> "No"
    ReadingFlow.VERTICAL -> null
}

private fun PageType.toComicInfoValue(): String = when (this) {
    PageType.FRONT_COVER -> "FrontCover"
    PageType.INNER_COVER -> "InnerCover"
    PageType.ROUNDUP -> "Roundup"
    PageType.STORY -> "Story"
    PageType.ADVERTISEMENT -> "Advertisement"
    PageType.EDITORIAL -> "Editorial"
    PageType.LETTERS -> "Letters"
    PageType.PREVIEW -> "Preview"
    PageType.BACK_COVER -> "BackCover"
    PageType.DELETED -> "Deleted"
    PageType.OTHER -> "Other"
}

private fun StringBuilder.appendPage(page: ComicInfoPage) {
    append("    <Page Image=\"").append(page.image).append('"')
    attribute("Type", page.type.toComicInfoValue())
    if (page.doublePage) attribute("DoublePage", "true")
    page.bookmark?.let { attribute("Bookmark", it) }
    page.widthPx?.let { attribute("ImageWidth", it.toString()) }
    page.heightPx?.let { attribute("ImageHeight", it.toString()) }
    append(" />").append('\n')
}

private fun StringBuilder.element(name: String, value: String?) {
    if (value == null) return
    append("  <").append(name).append('>')
    appendEscaped(value, inAttribute = false)
    append("</").append(name).append('>').append('\n')
}

private fun StringBuilder.attribute(name: String, value: String) {
    append(' ').append(name).append("=\"")
    appendEscaped(value, inAttribute = true)
    append('"')
}

/**
 * Escapes [value] and drops what XML 1.0 cannot represent at all.
 *
 * Escaping is the obvious half: a series called "Hellboy & Co." or a title containing "<" would
 * otherwise produce a document our own parser rejects, turning one odd book into no metadata.
 *
 * Dropping is the half that is easy to miss. Most control characters are illegal in XML 1.0 in
 * *any* form — `&#1;` is not a rescue, it is a second way to be invalid — and metadata reaching an
 * export can come from a filename, so it can contain anything. Unpaired surrogates go the same way;
 * a correct pair is one code point here and survives.
 */
private fun StringBuilder.appendEscaped(value: String, inAttribute: Boolean) {
    var i = 0
    while (i < value.length) {
        val point = value.codePointAt(i)
        i += Character.charCount(point)
        if (!isLegalXmlChar(point)) continue
        when {
            point == '&'.code -> append("&amp;")
            point == '<'.code -> append("&lt;")
            point == '>'.code -> append("&gt;")
            inAttribute && point == '"'.code -> append("&quot;")
            else -> appendCodePoint(point)
        }
    }
}

private const val MAX_BMP_LEGAL = 0xFFFD
private const val SURROGATE_FIRST = 0xD800
private const val SURROGATE_LAST = 0xDFFF
private const val SUPPLEMENTARY_FIRST = 0x10000
private const val UNICODE_LAST = 0x10FFFF

/** XML 1.0 §2.2: tab, LF, CR, then the BMP minus surrogates and the two noncharacters, then above. */
private fun isLegalXmlChar(point: Int): Boolean = when {
    point == '\t'.code || point == '\n'.code || point == '\r'.code -> true
    point < ' '.code -> false
    point in SURROGATE_FIRST..SURROGATE_LAST -> false
    point <= MAX_BMP_LEGAL -> true
    else -> point in SUPPLEMENTARY_FIRST..UNICODE_LAST
}
