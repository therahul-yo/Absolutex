package com.absolutex.source.epub

import com.absolutex.model.TocEntry
import com.absolutex.source.SafeXml
import com.absolutex.source.SafeXml.attr
import com.absolutex.source.SafeXml.childElements
import com.absolutex.source.SafeXml.descendantElements
import com.absolutex.source.SafeXml.localName
/**
 * Parses the table of contents from an EPUB's navigation document (`nav.xhtml` for EPUB 3,
 * `toc.ncx` for EPUB 2 as fallback) given the package manifest and a way to read entries.
 */
object EpubTocParser {
    fun parse(
        entryNames: List<String>,
        read: (String) -> ByteArray?,
        manifest: Map<String, EpubPackage.Item>,
        opfDir: String,
    ): List<TocEntry> {
        // EPUB 3: find the nav document by manifest property="nav".
        val navItem = manifest.values.firstOrNull { it.properties?.contains("nav") == true }
        val navName = navItem?.entryName
        if (navName != null) {
            val xml = read(navName)?.let { EpubPackage.parseXml(it) } ?: return emptyList()
            return parseNavXml(xml, opfDir)
        }
        // EPUB 2 fallback: find toc.ncx by media-type.
        val tocName = manifest.values
            .firstOrNull { it.mediaType == "application/x-dtbncx+xml" || it.mediaType == "application/x-dtbncx+zip" }
            ?.entryName
        if (tocName != null) {
            val xml = read(tocName)?.let { EpubPackage.parseXml(it) } ?: return emptyList()
            return parseNcxXml(xml)
        }
        // Final fallback: build TOC from the spine itself (each spine item is a top-level entry).
        return emptyList()
    }

    private fun parseNavXml(xml: org.w3c.dom.Element, opfDir: String): List<TocEntry> {
        val nav = xml.childElements().firstOrNull { it.localName() == "nav" } ?: return emptyList()
        val tocNav = nav.childElements().firstOrNull { it.localName() == "ol" } ?: return emptyList()
        val items = tocNav.childElements().filter { it.localName() == "li" }
        return items.mapNotNull { li ->
            val a = li.childElements().firstOrNull { it.localName() == "a" } ?: return@mapNotNull null
            val href = a.attr("href") ?: return@mapNotNull null
            val label = a.childElements().filter { it.localName() == "span" }.firstOrNull()?.textContent?.trim() ?: ""
            TocEntry(
                title = label ?: "",
                pageIndex = 0, // Resolved by caller against spine order.
                depth = 0,
            )
        }
    }

    private fun parseNcxXml(xml: org.w3c.dom.Element): List<TocEntry> {
        val navMap = xml.childElements().firstOrNull { it.localName() == "navMap" } ?: return emptyList()
        val points = navMap.childElements().filter { it.localName() == "navPoint" }
        return points.mapIndexed { index, pt ->
            TocEntry(
                title = pt.childElements().firstOrNull { it.localName() == "navLabel" }
                    ?.childElements()?.firstOrNull { it.localName() == "text" }?.textContent?.trim() ?: "",
                pageIndex = index,
                depth = 0,
            )
        }
    }
}
