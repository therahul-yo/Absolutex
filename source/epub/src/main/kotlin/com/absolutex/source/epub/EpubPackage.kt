package com.absolutex.source.epub

import com.absolutex.model.ReadingFlow
import com.absolutex.source.EntryFilter
import com.absolutex.source.SafeXml
import com.absolutex.source.SafeXml.attr
import com.absolutex.source.SafeXml.childElements
import com.absolutex.source.SafeXml.descendantElements
import com.absolutex.source.SafeXml.localName
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

/**
 * Reads a fixed-layout comic EPUB's page order out of its package document (§2, §6).
 *
 * There is no EPUB API on Android and this adds no native code, because none is needed: an EPUB
 * is a ZIP, and the reader already opens ZIPs. What is left is the part that is genuinely EPUB —
 * `META-INF/container.xml` names the package document, the package document's manifest maps ids
 * to files and its spine puts those files in reading order, and each spine document references
 * the one image that is that page.
 *
 * Deliberately pure: it is handed the entry names and a way to read one, so every malformation
 * below is a JVM test rather than something only a real book on a real device can produce.
 *
 * Contract, inherited from [com.absolutex.source.ComicInfoParser]: malformed input is a verdict,
 * never an exception. A file this cannot understand becomes [EpubBook.Malformed].
 */
object EpubPackage {

    /** Where every EPUB says its package document is. Fixed by the container specification. */
    private const val CONTAINER_XML = "META-INF/container.xml"

    /**
     * Largest XML document this will parse, matching [com.absolutex.source.ComicInfoParser]'s cap
     * and for the same reason: these arrive compressed from inside an untrusted container, where a
     * small entry can inflate into a DOM that exhausts memory.
     */
    const val MAX_XML_BYTES = 1L * 1024 * 1024

    /**
     * @param entryNames every entry in the container, as the ZIP knows them.
     * @param read one entry's bytes by name, or null when it cannot be read. Called once for
     *   `container.xml`, once for the package document, and once per spine document — see the
     *   note on cost in the module's PR; a book whose spine is long pays one read per page here.
     */
    fun parse(entryNames: List<String>, read: (name: String) -> ByteArray?): EpubBook {
        val names = entryNames.associateBy { it.lowercase() }
        val containerXml = names[CONTAINER_XML.lowercase()]?.let(read) ?: return EpubBook.Malformed
        val opfName = rootfilePath(containerXml)?.let { names[it.lowercase()] } ?: return EpubBook.Malformed
        val opf = read(opfName)?.let(::parseXml) ?: return EpubBook.Malformed
        return readPackage(opf, EpubPath.parentOf(opfName), names, read)
    }

    private fun readPackage(
        opf: Element,
        opfDir: String,
        names: Map<String, String>,
        read: (name: String) -> ByteArray?,
    ): EpubBook {
        val manifest = manifestOf(opf, opfDir, names)
        val spine = opf.childElements().lastOrNull { it.localName() == "spine" } ?: return EpubBook.Malformed
        val documents = spine.childElements()
            .filter { it.localName() == "itemref" }
            .mapNotNull { it.attr("idref") }
            .mapNotNull { manifest[it]?.entryName }
        if (documents.isEmpty()) return EpubBook.Malformed

        val pages = documents.mapNotNull { document -> pageImageOf(document, names, read) }
        // Every spine document must be a page. A book where only some resolve is not a comic
        // whose pages are missing — it is a text EPUB with a picture in it, and opening it as a
        // comic would silently drop its prose.
        if (pages.size < documents.size) return EpubBook.Reflowable

        return EpubBook.Pages(
            entryNames = pages,
            readingFlow = if (spine.attr("page-progression-direction")?.lowercase() == "rtl") {
                ReadingFlow.RTL
            } else {
                null
            },
            coverEntryName = coverOf(opf, manifest, pages),
        )
    }

    /** One manifest entry: where the file is, and what the package says it is. */
    private data class Item(val entryName: String, val mediaType: String?, val properties: String?)

    private fun manifestOf(opf: Element, opfDir: String, names: Map<String, String>): Map<String, Item> {
        val manifest = opf.childElements().lastOrNull { it.localName() == "manifest" } ?: return emptyMap()
        return manifest.childElements()
            .filter { it.localName() == "item" }
            .mapNotNull { item ->
                val id = item.attr("id") ?: return@mapNotNull null
                val href = item.attr("href") ?: return@mapNotNull null
                // Resolved against the ZIP's real names, so a manifest entry naming a file the
                // container does not hold simply is not an item.
                val entry = EpubPath.resolve(opfDir, href)?.let { names[it.lowercase()] }
                    ?: return@mapNotNull null
                id to Item(entry, item.attr("media-type")?.lowercase(), item.attr("properties")?.lowercase())
            }
            .toMap()
    }

    /**
     * The single image a fixed-layout page document displays, or null if it shows none.
     *
     * Both spellings are looked for because both are used: Kindle Comic Creator and its
     * descendants wrap the page in `<svg><image xlink:href="..."/></svg>` so it scales to the
     * viewport, while simpler producers emit a plain `<img src="...">`. The first image in
     * document order wins — a page with a decorative second image is still that page.
     */
    private fun pageImageOf(
        documentEntry: String,
        names: Map<String, String>,
        read: (name: String) -> ByteArray?,
    ): String? {
        val document = read(documentEntry)?.let(::parseXml) ?: return null
        val baseDir = EpubPath.parentOf(documentEntry)
        for (element in document.descendantElements()) {
            val href = when (element.localName()) {
                "img" -> element.attr("src")
                "image" -> element.attr("href")      // xlink:href folds to href — see SafeXml.attr
                else -> null
            } ?: continue
            val entry = EpubPath.resolve(baseDir, href)?.let { names[it.lowercase()] } ?: continue
            // An entry that is not an image is not a page, whatever the markup called it.
            if (EntryFilter.isPage(entry)) return entry
        }
        return null
    }

    /**
     * The cover image, by either of the two ways an EPUB declares one: EPUB 3's manifest
     * `properties="cover-image"`, or EPUB 2's `<meta name="cover" content="itemId"/>`.
     *
     * Null when the declared cover is also the first page, because the library already shows the
     * first page and a cover that duplicates it is not a separate thing to carry.
     */
    private fun coverOf(opf: Element, manifest: Map<String, Item>, pages: List<String>): String? {
        val declared = manifest.values
            .firstOrNull { it.properties?.contains("cover-image") == true }
            ?.entryName
            ?: opf.childElements()
                .filter { it.localName() == "metadata" }
                .flatMap { it.childElements() }
                .lastOrNull { it.localName() == "meta" && it.attr("name")?.lowercase() == "cover" }
                ?.attr("content")
                ?.let { manifest[it] }
                ?.takeIf { it.mediaType?.startsWith("image/") != false }
                ?.entryName
        return declared?.takeIf { it != pages.firstOrNull() }
    }

    /** The `full-path` of the first rootfile in container.xml. */
    private fun rootfilePath(containerXml: ByteArray): String? {
        val root = parseXml(containerXml) ?: return null
        return root.descendantElements()
            .firstOrNull { it.localName() == "rootfile" }
            ?.attr("full-path")
            ?.let { EpubPath.resolve("", it) }
    }

    // The catches are the "a verdict, never an exception" contract: this parses attacker-
    // controlled XML out of a container the app did not make, and any failure means "not a book
    // we can open", not a crash. Not logged: :source:epub is pure Kotlin with no logger, and a
    // corrupt book would spam whatever it had, once per spine document.
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun parseXml(bytes: ByteArray): Element? {
        if (bytes.size.toLong() > MAX_XML_BYTES) return null
        return try {
            SafeXml.newBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            // A deeply nested document can exhaust the stack inside the DOM builder; "never
            // throw" is absolute here, so this one Error is caught by name.
            null
        }
    }
}
