package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4: modern formats + hardening of the §2 filter (all JVM, no device needed). */
class EntryFilterExtTest {

    @Test fun `jxl and jpeg2000 family are pages`() {
        assertTrue(EntryFilter.isPage("ch1/page01.jxl"))
        assertTrue(EntryFilter.isPage("ch1/page01.JXL"))
        assertTrue(EntryFilter.isPage("scan.jp2"))
        assertTrue(EntryFilter.isPage("scan.jpx"))
        assertTrue(EntryFilter.isPage("scan.j2k"))
    }

    @Test fun `macosx resource fork dir matches in any case`() {
        assertTrue(EntryFilter.isJunk("__MACOSX/foo.jpg"))
        assertTrue(EntryFilter.isJunk("__MacOSX/foo.jpg"))
        assertTrue(EntryFilter.isJunk("a/__macosx/b.jpg"))
        // ...but a real page must still be a page.
        assertFalse(EntryFilter.isJunk("Absolute Batman 001 (2024) 001.jpg"))
    }

    @Test fun `backslash paths yield the right extension`() {
        assertEquals("jpg", EntryFilter.extensionOf("ch1\\page01.JPG"))
        assertEquals("jxl", EntryFilter.extensionOf("ch1\\page01.jxl"))
        assertTrue(EntryFilter.isPage("ch1\\page01.png"))
        assertTrue(EntryFilter.isJunk("archive\\__MACOSX\\._page01.jpg"))
    }

    @Test fun `junk names match in any case, pages do not`() {
        assertTrue(EntryFilter.isJunk("THUMBS.DB"))
        assertTrue(EntryFilter.isJunk("x/.ds_store"))
        assertTrue(EntryFilter.isJunk("DESKTOP.INI"))
        assertFalse(EntryFilter.isPage("Thumbs.db"))
    }

    @Test fun `corpus names are pages, trailers included`() {
        assertTrue(EntryFilter.isPage("Absolute Batman 001 (2024) 001.jpg"))
        assertTrue(EntryFilter.isPage("ZZZZZ.jpg"))
        assertFalse(EntryFilter.isPage("ComicInfo.xml"))
    }
}
