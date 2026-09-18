package com.absolutex.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The one decoder for a book's [LibraryBook.path]: a plain filesystem path via [java.io.File], or
 * a SAF document Uri whose document id is decoded and split by hand. [BookPath] must decode the
 * id and split IT, not the raw Uri, to recover the real parent and name (finding 1 that shipped
 * with §5.2's auto-advance) — and must also drop the id's own `<root>:` prefix, or a document at
 * a tree's root comes back named "primary:Batman 001.cbz" instead of "Batman 001.cbz".
 */
class BookPathTest {

    private val treeRoot = "content://com.android.externalstorage.documents/tree/primary%3AComics"

    private fun docUri(id: String) = "$treeRoot/document/${percentEncode(id)}"

    /** A minimal stand-in for `android.net.Uri.encode`: percent-encodes ':', '/', ' ' and '%'. */
    private fun percentEncode(raw: String): String = buildString {
        for (c in raw) when (c) {
            ':' -> append("%3A")
            '/' -> append("%2F")
            ' ' -> append("%20")
            '%' -> append("%25")
            else -> append(c)
        }
    }

    @Test fun `a filesystem path splits the same way java io File does`() {
        assertEquals("/lib/one", BookPath.parentOf("/lib/one/Batman 001.cbz"))
        assertEquals("Batman 001.cbz", BookPath.nameOf("/lib/one/Batman 001.cbz"))
    }

    @Test fun `two SAF documents in the same folder share a parent`() {
        val a = docUri("primary:Comics/Chapter 1/001.jpg")
        val b = docUri("primary:Comics/Chapter 1/002.jpg")
        assertEquals(BookPath.parentOf(a), BookPath.parentOf(b))
        assertEquals("001.jpg", BookPath.nameOf(a))
        assertEquals("002.jpg", BookPath.nameOf(b))
    }

    @Test fun `two SAF documents in different nested folders do not share a parent`() {
        val a = docUri("primary:Comics/Chapter 1/001.jpg")
        val b = docUri("primary:Comics/Chapter 2/001.jpg")
        assertNotEquals(BookPath.parentOf(a), BookPath.parentOf(b))
    }

    @Test fun `a SAF document at the tree root has the tree itself as its parent`() {
        val a = docUri("primary:Comics/Batman 001.cbz")
        val b = docUri("primary:Comics/Batman 002.cbz")
        assertEquals(BookPath.parentOf(a), BookPath.parentOf(b))
        assertEquals("Batman 001.cbz", BookPath.nameOf(a))
    }

    @Test fun `names with spaces and percent signs decode correctly`() {
        val uri = docUri("primary:Comics/50% Off Sale/Batman 001.cbz")
        assertEquals("Batman 001.cbz", BookPath.nameOf(uri))
        assertEquals(BookPath.parentOf(uri), BookPath.parentOf(docUri("primary:Comics/50% Off Sale/Batman 002.cbz")))
    }

    @Test fun `a malformed content uri falls back instead of throwing`() {
        val malformed = "content://authority/tree/x/document/bad%"
        assertEquals("bad%", BookPath.nameOf(malformed))
    }

    // Moved from SAFFilenameDecoderTest (#33 review): a document id with no "/" at all still
    // carries its `<root>:` prefix, which must not leak into the name.

    @Test fun `a document id with no slash still drops its root prefix`() {
        val encoded = "content://downloads/document/primary%3ABatman%20001.cbz"
        assertEquals("Batman 001.cbz", BookPath.nameOf(encoded))
    }

    @Test fun `an already-decoded document id still drops its root prefix`() {
        assertEquals("001.cbz", BookPath.nameOf("content://downloads/document/primary:001.cbz"))
    }

    @Test fun `a non-content path with no document segment falls back to the file name`() {
        assertEquals("unknown", BookPath.nameOf("unknown"))
    }
}
