package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test fun `digits compare numerically not lexically`() {
        assertEquals(
            listOf("page2.jpg", "page10.jpg", "page100.jpg"),
            sorted("page100.jpg", "page10.jpg", "page2.jpg"),
        )
    }

    @Test fun `comparison is case insensitive`() {
        assertEquals(
            listOf("Page2.jpg", "pAGE10.jpg"),
            sorted("pAGE10.jpg", "Page2.jpg"),
        )
    }

    @Test fun `leading zeros do not change numeric value`() {
        assertEquals(
            listOf("p007.jpg", "p8.jpg", "p0010.jpg"),
            sorted("p0010.jpg", "p8.jpg", "p007.jpg"),
        )
    }

    @Test fun `equal value differing padding is deterministic, shorter first`() {
        assertEquals(listOf("1.jpg", "01.jpg", "001.jpg"), sorted("001.jpg", "01.jpg", "1.jpg"))
    }

    @Test fun `subfolder pages stay contiguous`() {
        // Without separator ranking, "ch1.jpg" would sort between "ch1/" entries.
        assertEquals(
            listOf("ch1/002.jpg", "ch1/010.jpg", "ch1.jpg", "ch2/001.jpg"),
            sorted("ch2/001.jpg", "ch1.jpg", "ch1/010.jpg", "ch1/002.jpg"),
        )
    }

    @Test fun `digit runs beyond Long range do not overflow`() {
        val huge = "p99999999999999999999999.jpg"
        val bigger = "p999999999999999999999999.jpg"
        assertEquals(listOf(huge, bigger), sorted(bigger, huge))
    }

    @Test fun `real corpus - Absolute Batman entry names order correctly`() {
        // Exact names from Absolute Batman 001 (2024).cbr, including its ZZZZZ.jpg trailer.
        val actual = sorted(
            "ZZZZZ.jpg",
            "Absolute Batman 001 (2024) 010.jpg",
            "Absolute Batman 001 (2024) 002.jpg",
            "Absolute Batman 001 (2024) 001.jpg",
        )
        assertEquals(
            listOf(
                "Absolute Batman 001 (2024) 001.jpg",
                "Absolute Batman 001 (2024) 002.jpg",
                "Absolute Batman 001 (2024) 010.jpg",
                "ZZZZZ.jpg",
            ),
            actual,
        )
    }

    @Test fun `non-ascii and RTL filenames do not throw and are ordered stably`() {
        val names = arrayOf("مانجا-10.jpg", "مانجا-2.jpg", "漫画3.jpg", "漫画20.jpg")
        val out = sorted(*names)
        assertTrue(out.indexOf("مانجا-2.jpg") < out.indexOf("مانجا-10.jpg"))
        assertTrue(out.indexOf("漫画3.jpg") < out.indexOf("漫画20.jpg"))
    }

    @Test fun `comparator is symmetric and reflexive`() {
        val names = listOf("a1.jpg", "A10.jpg", "b/1.jpg", "1.jpg", "ZZZZZ.jpg", "a1.jpg")
        for (x in names) {
            assertEquals(0, NaturalOrder.compare(x, x))
            for (y in names) {
                val f = NaturalOrder.compare(x, y)
                val r = NaturalOrder.compare(y, x)
                assertEquals("asymmetric for '$x' vs '$y'", 0, f + r)
            }
        }
    }
}

class EntryFilterTest {

    @Test fun `real page extensions are pages`() {
        assertTrue(EntryFilter.isPage("Absolute Batman 001 (2024) 001.jpg"))
        assertTrue(EntryFilter.isPage("ch1/page.AVIF"))
        assertTrue(EntryFilter.isPage("a.webp"))
    }

    @Test fun `junk is filtered`() {
        assertTrue(EntryFilter.isJunk("__MACOSX/foo.jpg"))
        assertTrue(EntryFilter.isJunk("a/__MACOSX/b.jpg"))
        assertTrue(EntryFilter.isJunk("Thumbs.db"))
        assertTrue(EntryFilter.isJunk("x/.DS_Store"))
        assertTrue(EntryFilter.isJunk("._page1.jpg"))
        assertTrue(EntryFilter.isJunk("chapter1/"))
    }

    @Test fun `metadata and text are not pages`() {
        assertFalse(EntryFilter.isPage("ComicInfo.xml"))
        assertFalse(EntryFilter.isPage("readme.txt"))
        assertFalse(EntryFilter.isPage("noextension"))
    }

    @Test fun `a folder named like an image is not a page`() {
        assertFalse(EntryFilter.isPage("weird.jpg/"))
    }
}
