package com.absolutex.source

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/**
 * A DOM builder hardened for XML that came out of an archive the app did not make.
 *
 * Every XML this app reads is untrusted: a `ComicInfo.xml`, an EPUB's `container.xml` and its
 * package document all arrive inside a container a stranger produced. The switches below are the
 * difference between parsing one and handing an attacker a file reader, so they belong in one
 * place rather than being retyped — correctly — in each parser.
 *
 * The JDK's own DOM, no XML dependency (§5.2). DOM rather than SAX because these documents are
 * kilobytes and the tree costs nothing next to how much clearer the code reads.
 *
 * `ComicInfoParser` still carries its own copy of this hardening; folding it onto this one is a
 * follow-up held only because `:source:api` is shared with an open PR.
 */
object SafeXml {

    /**
     * A parser that resolves nothing it was not given.
     *
     * Each switch is set independently because Android's XML stack and the JDK's do not accept
     * the same feature names, and one unsupported name must not silently disable the rest.
     */
    fun newBuilder(): DocumentBuilder = factory().newDocumentBuilder().apply {
        // Silence the default handler: a corrupt archive must not spray SAX warnings at stderr.
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) = Unit
            override fun fatalError(exception: SAXParseException): Unit = throw exception
        })
    }

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        trySet { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        trySet { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        trySet { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        trySet { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        trySet { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        trySet { isXIncludeAware = false }
        trySet { isExpandEntityReferences = false }
        isValidating = false
        // Namespace-unaware on purpose: EPUB documents are namespaced but the prefixes real
        // files use vary, so every lookup here folds the prefix away (see [localName]) instead
        // of depending on a producer having chosen the prefix we expected.
        isNamespaceAware = false
    }

    private inline fun trySet(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Exception) {
            // Feature unsupported by this XML implementation; the remaining hardening still applies.
        }
    }

    /** Direct element children, in document order. */
    fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    /** Every descendant element, in document order — an EPUB's page image can be nested anywhere. */
    fun Element.descendantElements(): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            for (child in element.childElements()) {
                out.add(child)
                walk(child)
            }
        }
        walk(this)
        return out
    }

    /** Tag name without its namespace prefix, lowercased. */
    fun Element.localName(): String = tagName.substringAfterLast(':').lowercase()

    /**
     * Attribute lookup by lowercased local name.
     *
     * Folded rather than exact because the same attribute reaches us spelled several ways: an
     * EPUB's SVG image reference is `xlink:href` in one file and plain `href` in the next.
     */
    fun Element.attr(lowercaseName: String): String? {
        val attributes = attributes ?: return null
        for (i in 0 until attributes.length) {
            val item = attributes.item(i) ?: continue
            if (item.nodeName.substringAfterLast(':').lowercase() == lowercaseName) {
                return item.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
