package com.absolutex.source.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An EPUB href is a URL, not a path, and it is relative to whichever document holds it. These pin
 * the three things that follow from that and the one thing that must never follow: a reference
 * that leaves the container.
 */
class EpubPathTest {

    @Test fun `a sibling reference resolves beside its document`() {
        assertEquals("OEBPS/page001.xhtml", EpubPath.resolve("OEBPS", "page001.xhtml"))
    }

    @Test fun `a reference from the container root has no folder to join`() {
        assertEquals("content.opf", EpubPath.resolve("", "content.opf"))
    }

    @Test fun `a reference climbs out of its folder into a sibling one`() {
        // The shape every Kindle Comic Creator book uses: text/ documents pointing at image/.
        assertEquals("OEBPS/image/p1.jpg", EpubPath.resolve("OEBPS/text", "../image/p1.jpg"))
    }

    @Test fun `dot segments collapse`() {
        assertEquals("OEBPS/a/p1.jpg", EpubPath.resolve("OEBPS", "./a/./p1.jpg"))
        assertEquals("OEBPS/p1.jpg", EpubPath.resolve("OEBPS/text/deep", "../../p1.jpg"))
    }

    @Test fun `empty segments collapse`() {
        assertEquals("OEBPS/a/p1.jpg", EpubPath.resolve("OEBPS", "a//p1.jpg"))
    }

    @Test fun `a leading slash means the container root, not the filesystem root`() {
        assertEquals("OEBPS/p1.jpg", EpubPath.resolve("OEBPS/text", "/OEBPS/p1.jpg"))
    }

    @Test fun `percent escapes decode`() {
        assertEquals("OEBPS/page 001.jpg", EpubPath.resolve("OEBPS", "page%20001.jpg"))
        assertEquals("OEBPS/ページ.jpg", EpubPath.resolve("OEBPS", "%E3%83%9A%E3%83%BC%E3%82%B8.jpg"))
    }

    @Test fun `a literal plus stays a plus`() {
        // This is a path, not a form: URLDecoder would turn "+" into a space and break the lookup
        // of a file genuinely named with one.
        assertEquals("OEBPS/a+b.jpg", EpubPath.resolve("OEBPS", "a+b.jpg"))
    }

    @Test fun `a fragment is not part of the entry name`() {
        assertEquals("OEBPS/page001.xhtml", EpubPath.resolve("OEBPS", "page001.xhtml#panel3"))
    }

    // ---- what must not resolve ----------------------------------------------------------

    @Test fun `a path climbing above the container root is refused, not clamped`() {
        // Clamping would silently turn this into "secret.jpg" and could match a real entry the
        // document never asked for. Refusing means a traversal attempt never becomes a lookup.
        assertNull(EpubPath.resolve("OEBPS", "../../secret.jpg"))
        assertNull(EpubPath.resolve("", "../secret.jpg"))
    }

    @Test fun `a remote reference is refused`() {
        // Nothing leaves the device unless the user asked for it.
        assertNull(EpubPath.resolve("OEBPS", "https://example.test/p1.jpg"))
        assertNull(EpubPath.resolve("OEBPS", "http://example.test/p1.jpg"))
        assertNull(EpubPath.resolve("OEBPS", "//example.test/p1.jpg"))
        assertNull(EpubPath.resolve("OEBPS", "data:image/png;base64,AAAA"))
    }

    @Test fun `an empty or fragment-only reference resolves to nothing`() {
        assertNull(EpubPath.resolve("OEBPS", ""))
        assertNull(EpubPath.resolve("OEBPS", "   "))
        assertNull(EpubPath.resolve("OEBPS", "#panel3"))
    }

    @Test fun `a reference to a folder resolves to that folder, and is simply not a page`() {
        // "." designates the containing folder. Resolving it to the folder's own name is the
        // honest answer; refusing it here would be the resolver deciding what counts as a page,
        // which is the caller's job — and EntryFilter.isPage rejects a folder name anyway.
        assertEquals("OEBPS", EpubPath.resolve("OEBPS", "."))
        // From the container root there is no folder left to name, so it collapses to nothing.
        assertNull(EpubPath.resolve("", "."))
    }

    @Test fun `parentOf is the folder, empty at the root`() {
        assertEquals("OEBPS/text", EpubPath.parentOf("OEBPS/text/p1.xhtml"))
        assertEquals("", EpubPath.parentOf("content.opf"))
    }
}
