package com.absolutex.source.epub

import com.absolutex.model.TocEntry
import com.absolutex.source.SafeXml.attr
import com.absolutex.source.SafeXml.childElements
import com.absolutex.source.SafeXml.descendantElements
import com.absolutex.source.SafeXml.localName
import org.w3c.dom.Element

/**
 * A text EPUB's table of contents, as the book itself declares it.
 *
 * EPUB 3 books carry a navigation document (the manifest item with `properties="nav"`), whose
 * `<nav epub:type="toc">` is nested `<ol>` lists of links. EPUB 2 books carry an NCX instead
 * (named by the spine's `toc` attribute), a tree of `navPoint`s. Many books carry both; the nav
 * document wins, and the NCX is only read when there is no usable nav.
 *
 * Each entry points at a spine document, so [TocEntry.pageIndex] is a spine index: the text
 * reader's chapter. An entry naming a file outside the spine, or nothing at all, is dropped: it has
 * nowhere to go. Same contract as [EpubPackage]: malformed input yields an empty list, never an
 * exception, and the reader then simply shows no contents button.
 */
object EpubToc {

    /**
     * A web novel's navigation document lists every chapter, and 2,700 chapters make a document
     * well past the package document's 1 MiB cap. Still bounded: it arrives from an untrusted
     * container, and a DOM grows several times the size of its source.
     */
    const val MAX_NAV_BYTES = 8L * 1024 * 1024

    /** Past this, a contents list is noise, and a hostile one is only trying to exhaust memory. */
    const val MAX_ENTRIES = 50_000

    /** Real contents nest two or three deep. A deeper tree is malformed, and recursion has a stack. */
    const val MAX_DEPTH = 8

    /**
     * @param spine the book's spine as archive entry names, as [EpubBook.Reflowable.spine] has it.
     */
    fun parse(entryNames: List<String>, spine: List<String>, read: (name: String) -> ByteArray?): List<TocEntry> {
        val names = entryNames.associateBy { it.lowercase() }
        val container = names[EpubPackage.CONTAINER_XML.lowercase()]?.let(read) ?: return emptyList()
        val opfName = EpubPackage.rootfilePath(container)?.let { names[it.lowercase()] } ?: return emptyList()
        val opf = read(opfName)?.let { EpubPackage.parseXml(it) } ?: return emptyList()
        val items = manifestOf(opf, EpubPath.parentOf(opfName), names)
        val chapters = spine.withIndex().associate { (index, entry) -> entry.lowercase() to index }
        val context = Context(names, chapters)

        val nav = items.firstOrNull { "nav" in it.properties }?.let { readTree(it.entry, read) }
        val fromNav = nav?.let { navEntries(it, context.from(nav.second)) }.orEmpty()
        if (fromNav.isNotEmpty()) return fromNav

        val spineElement = opf.childElements().lastOrNull { it.localName() == "spine" }
        val ncxItem = spineElement?.attr("toc")?.let { id -> items.firstOrNull { it.id == id } }
            ?: items.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
        val ncx = ncxItem?.let { readTree(it.entry, read) } ?: return emptyList()
        return ncxEntries(ncx.first, context.from(ncx.second))
    }

    private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"

    private data class Item(val id: String, val entry: String, val mediaType: String?, val properties: List<String>)

    /** Resolves hrefs against the folder of the document they appear in, down to a spine index. */
    private class Context(val names: Map<String, String>, val chapters: Map<String, Int>, val baseDir: String = "") {
        fun from(document: String) = Context(names, chapters, EpubPath.parentOf(document))

        fun chapterOf(href: String?): Int? {
            val entry = href?.let { EpubPath.resolve(baseDir, it) }?.let { names[it.lowercase()] } ?: return null
            return chapters[entry.lowercase()]
        }
    }

    private fun manifestOf(opf: Element, opfDir: String, names: Map<String, String>): List<Item> {
        val manifest = opf.childElements().lastOrNull { it.localName() == "manifest" } ?: return emptyList()
        return manifest.childElements().filter { it.localName() == "item" }.mapNotNull { item ->
            val id = item.attr("id") ?: return@mapNotNull null
            val entry = item.attr("href")?.let { EpubPath.resolve(opfDir, it) }?.let { names[it.lowercase()] }
                ?: return@mapNotNull null
            val properties = item.attr("properties")?.lowercase()?.split(' ').orEmpty()
            Item(id, entry, item.attr("media-type")?.lowercase(), properties)
        }
    }

    /** The document's root element, paired with its entry name for resolving the hrefs inside it. */
    private fun readTree(entry: String, read: (name: String) -> ByteArray?): Pair<Element, String>? =
        read(entry)?.let { EpubPackage.parseXml(it, MAX_NAV_BYTES) }?.let { it to entry }

    /** The `toc` nav's outline. A nav document with no typed nav falls back to its first one. */
    private fun navEntries(document: Pair<Element, String>, context: Context): List<TocEntry> {
        val navs = document.first.descendantElements().filter { it.localName() == "nav" }
        val toc = navs.firstOrNull { it.attr("type")?.lowercase()?.split(' ')?.contains("toc") == true }
            ?: navs.firstOrNull()
            ?: return emptyList()
        val out = ArrayList<TocEntry>()
        toc.childElements().filter { it.localName() == "ol" }.forEach { walkList(it, 0, context, out) }
        return out
    }

    /** One `<ol>`: each `<li>` is a link (or an unlinked heading) and may nest its own `<ol>`. */
    private fun walkList(list: Element, depth: Int, context: Context, out: MutableList<TocEntry>) {
        if (depth > MAX_DEPTH) return
        for (item in list.childElements()) {
            if (out.size >= MAX_ENTRIES) return
            if (item.localName() != "li") continue
            val label = item.childElements().firstOrNull { it.localName() == "a" || it.localName() == "span" }
            val chapter = context.chapterOf(label?.takeIf { it.localName() == "a" }?.attr("href"))
            val title = label?.let(::textOf)
            if (chapter != null && !title.isNullOrEmpty()) out += TocEntry(title, chapter, depth)
            item.childElements().filter { it.localName() == "ol" }.forEach { walkList(it, depth + 1, context, out) }
        }
    }

    private fun ncxEntries(ncx: Element, context: Context): List<TocEntry> {
        val navMap = ncx.childElements().firstOrNull { it.localName() == "navmap" } ?: return emptyList()
        val out = ArrayList<TocEntry>()
        walkPoints(navMap, 0, context, out)
        return out
    }

    /** NCX `navPoint`s, each a `navLabel/text` and a `content src`, nesting their own points. */
    private fun walkPoints(parent: Element, depth: Int, context: Context, out: MutableList<TocEntry>) {
        if (depth > MAX_DEPTH) return
        for (point in parent.childElements()) {
            if (out.size >= MAX_ENTRIES) return
            if (point.localName() != "navpoint") continue
            val title = point.childElements().firstOrNull { it.localName() == "navlabel" }
                ?.childElements()?.firstOrNull { it.localName() == "text" }
                ?.let(::textOf)
            val chapter = context.chapterOf(
                point.childElements().firstOrNull { it.localName() == "content" }?.attr("src"),
            )
            if (chapter != null && !title.isNullOrEmpty()) out += TocEntry(title, chapter, depth)
            walkPoints(point, depth + 1, context, out)
        }
    }

    /** A label's text with its whitespace collapsed: markup line breaks are not part of a title. */
    private fun textOf(element: Element): String = element.textContent.orEmpty().trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
