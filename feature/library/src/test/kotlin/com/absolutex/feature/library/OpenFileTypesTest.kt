package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's type list: every supported family present, and a fallback so nothing is unpickable. */
class OpenFileTypesTest {

    private val types = OpenFileTypes.MIME_TYPES.toList()

    @Test fun `every supported format has a type`() {
        val expected = listOf(
            "application/x-cbz", "application/zip", // cbz
            "application/x-cbr", "application/vnd.rar", // cbr
            "application/x-cb7", "application/x-7z-compressed", // cb7
            "application/x-cbt", "application/x-tar", // cbt
            "application/pdf",
            "application/epub+zip",
        )
        expected.forEach { assertTrue("missing $it", it in types) }
    }

    @Test fun `a catch-all comes last so an unknown type can still be picked`() {
        assertEquals(OpenFileTypes.ANY, types.last())
    }

    @Test fun `no type is listed twice`() {
        assertEquals(types.size, types.toSet().size)
    }

    @Test fun `every entry is a well-formed type`() {
        types.forEach { assertTrue("bad type $it", Regex("""[a-z*]+/[a-z0-9.+*-]+""").matches(it)) }
    }
}
