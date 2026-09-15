package com.absolutex.source

import com.absolutex.model.ComicInfo
import com.absolutex.model.ComicInfoPage
import com.absolutex.model.IssueNumber
import com.absolutex.model.PageType
import com.absolutex.model.ReadingFlow
import java.io.InputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/**
 * Reads the `ComicInfo.xml` that ships inside most scanned containers.
 *
 * Contract: malformed input returns null, never throws. The file comes from an archive we did not
 * make, so a corrupt one must cost the reader its metadata and nothing else.
 *
 * Parsed with the JDK's own DOM — no XML dependency (§5.2). DOM rather than SAX because the file
 * is a few KB at most, so the tree costs nothing and the code reads far clearer.
 */
object ComicInfoParser {

    /** ComicRack writes -1 into numeric fields it has no value for; that is "absent", not a number. */
    private const val COMICRACK_UNSET = -1

    fun parse(xml: String): ComicInfo? =
        // Reading from a Reader makes the parser ignore the document's own encoding declaration,
        // which is right once the bytes are already decoded. The InputStream overload keeps it, so
        // ComicRack's windows-1252 files still come out with their accents intact.
        parse(InputSource(StringReader(xml)))

    fun parse(input: InputStream): ComicInfo? = parse(InputSource(input))

    private fun parse(source: InputSource): ComicInfo? = try {
        val builder = newFactory().newDocumentBuilder().apply {
            // Silence the default handler: a corrupt archive must not spray SAX warnings at stderr.
            setErrorHandler(object : ErrorHandler {
                override fun warning(exception: SAXParseException) = Unit
                override fun error(exception: SAXParseException) = Unit
                override fun fatalError(exception: SAXParseException): Unit = throw exception
            })
        }
        val root = builder.parse(source).documentElement
        if (root == null || root.localTagName() != "comicinfo") {
            null
        } else {
            root.toComicInfo()
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // A deeply nested document can exhaust the stack inside the DOM builder. "Never throw" is
        // absolute here, so this one Error is caught by name rather than left to reach the reader.
        null
    }

    private fun newFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        // ComicInfo.xml arrives from an untrusted archive: no DTDs, no entities, no XInclude.
        // Each switch is set independently because Android's XML stack and the JDK's do not support
        // the same feature names, and one unsupported name must not disable the rest.
        trySet { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        trySet { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        trySet { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        trySet { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        trySet { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        trySet { isXIncludeAware = false }
        trySet { isExpandEntityReferences = false }
        isValidating = false
        // Element names are unqualified in every real ComicInfo.xml; staying namespace-unaware keeps
        // the lookups to plain tag names.
        isNamespaceAware = false
    }

    private inline fun trySet(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Exception) {
            // Feature unsupported by this XML implementation; the remaining hardening still applies.
        }
    }

    private fun Element.toComicInfo(): ComicInfo {
        val children = childElements()
        fun text(name: String): String? = children.lastOrNull { it.localTagName() == name }
            ?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        fun number(name: String): Int? =
            text(name)?.toIntOrNull()?.takeIf { it != COMICRACK_UNSET && it >= 0 }

        return ComicInfo(
            series = text("series"),
            number = text("number")?.let { IssueNumber.parse(it) },
            volume = number("volume"),
            title = text("title"),
            writers = children.filter { it.localTagName() == "writer" }
                .flatMap { (it.textContent ?: "").split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            year = number("year")?.takeIf { it in 1900..2199 },
            pageCount = number("pagecount"),
            pages = children.lastOrNull { it.localTagName() == "pages" }?.toPages().orEmpty(),
            readingFlow = when (text("manga")?.lowercase()) {
                "yesandrighttoleft" -> ReadingFlow.RTL
                // ComicRack wrote a bare "Yes" before the RTL variant existed, and every manga scan
                // carrying it reads right-to-left; calling it LTR would flip real books.
                "yes" -> ReadingFlow.RTL
                "no" -> ReadingFlow.LTR
                else -> null
            },
        )
    }

    private fun Element.toPages(): List<ComicInfoPage> = childElements()
        .filter { it.localTagName() == "page" }
        .mapNotNull { page ->
            val image = page.attr("image")?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            ComicInfoPage(
                image = image,
                type = PageType.fromComicInfo(page.attr("type")),
                doublePage = page.attr("doublepage").isXmlTrue(),
                bookmark = page.attr("bookmark"),
                widthPx = page.attr("imagewidth")?.toIntOrNull()?.takeIf { it > 0 },
                heightPx = page.attr("imageheight")?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    /** Tag name without any namespace prefix, lowercased — some tools emit `<series>`, not `<Series>`. */
    private fun Element.localTagName(): String = tagName.substringAfterLast(':').lowercase()

    /**
     * Attribute lookup by lowercased name. XML attribute names are case-sensitive and ComicRack
     * writes `Image`/`DoublePage`, but other tools write them lowercase, so we compare folded.
     */
    private fun Element.attr(lowercaseName: String): String? {
        val attributes = attributes ?: return null
        for (i in 0 until attributes.length) {
            val item = attributes.item(i) ?: continue
            if (item.nodeName.substringAfterLast(':').lowercase() == lowercaseName) {
                return item.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** XML booleans are "true"/"false", but ComicRack also emits "1"/"0". */
    private fun String?.isXmlTrue(): Boolean = when (this?.lowercase()) {
        "true", "1" -> true
        else -> false
    }
}
