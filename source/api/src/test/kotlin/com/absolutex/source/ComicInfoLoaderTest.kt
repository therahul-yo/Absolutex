package com.absolutex.source

import com.absolutex.model.PageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §2: ComicInfo.xml "exposed through ComicSource so the reader's spread layout can use it".
 *
 * The loader is the testable half: it locates the ComicInfo entry among an archive's raw
 * (ordinal-ordered) entry names and parses whatever the extractor hands back. The JNI half —
 * actually pulling those bytes out of a real container — is covered by LibArchiveSourceTest on
 * the device, because :source:libarchive cannot load its native library on the JVM.
 */
class ComicInfoLoaderTest {

    private val comicInfoXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo>
            <Series>Absolute Batman</Series>
            <Number>1</Number>
            <Year>2024</Year>
            <Pages>
                <Page Image="0" Type="FrontCover" DoublePage="false" />
                <Page Image="1" Type="Story" DoublePage="true" />
            </Pages>
        </ComicInfo>
    """.trimIndent()

    private fun loader(names: List<String>, extracted: ByteArray?) =
        ComicInfoLoader.from(names) { ordinal -> extracted?.let { ordinal to it } }

    @Test fun `finds ComicInfo whatever its casing or folder`() {
        val info = loader(listOf("META-INF/COMICINFO.XML", "page/001.jpg"), comicInfoXml.toByteArray())
        assertEquals("Absolute Batman", info?.series)
        assertEquals(2024, info?.year)
        assertEquals(PageType.FRONT_COVER, info?.pagesByImage?.get(0)?.type)
        assertEquals(true, info?.pagesByImage?.get(1)?.doublePage)
    }

    @Test fun `an archive without ComicInfo yields null`() {
        val info = loader(listOf("001.jpg", "002.jpg"), null)
        assertNull(info)
    }

    @Test fun `an extraction failure yields null, not a crash`() {
        val info = loader(listOf("ComicInfo.xml", "001.jpg"), null)
        assertNull(info)
    }

    @Test fun `the page entry ordinal is the raw archive ordinal, not the filtered index`() {
        // ComicInfo sits at raw ordinal 3, behind junk the filter drops; the locator must ask
        // for ordinal 3, not 1, or it would read a junk entry's bytes.
        val seen = ArrayList<Int>()
        val info = ComicInfoLoader.from(listOf("00.jpg", "__MACOSX/x", "ComicInfo.xml", "01.jpg")) { ordinal ->
            seen += ordinal
            ordinal to comicInfoXml.toByteArray()
        }
        assertEquals("Absolute Batman", info?.series)
        assertEquals(listOf(2), seen)
    }

    @Test fun `a corrupt ComicInfo costs the metadata and nothing else`() {
        val info = loader(listOf("ComicInfo.xml"), "<ComicInfo><Series>".toByteArray())
        assertNull(info)
    }

    @Test fun `wrongly typed image indices and absurd sizes stay safe`() {
        val hostile = """
            <?xml version="1.0"?>
            <ComicInfo><Pages>
                <Page Image="-3" Type="Story" />
                <Page Image="99999999999999999999" />
                <Page Image="2" ImageWidth="0" ImageHeight="-1" />
            </Pages></ComicInfo>
        """.trimIndent()
        val info = loader(listOf("ComicInfo.xml"), hostile.toByteArray())
        // The parser drops pages whose index is negative or overflows, and keeps the valid
        // index 2 with its absurd dimensions falling back to null. None of it may throw.
        assertEquals(1, info?.pages?.size)
        assertEquals(2, info?.pages?.single()?.image)
        assertNull(info?.pages?.single()?.widthPx)
        assertNull(info?.pages?.single()?.heightPx)
    }
}
