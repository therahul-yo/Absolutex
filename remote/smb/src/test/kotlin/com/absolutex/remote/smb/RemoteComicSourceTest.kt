package com.absolutex.remote.smb

import com.absolutex.model.Page
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** A zero-page book is unreadable, not an IndexOutOfBounds crash — for every future caller. */
class RemoteComicSourceTest {

    @Test fun `openCover on a zero-page book throws IOException`() {
        val empty = object : RemoteComicSource {
            override val pages: List<Page> = emptyList()

            override fun openPage(index: Int): InputStream = throw AssertionError("no pages")

            override fun close() = Unit
        }
        try {
            empty.openCover()
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message?.contains("no pages") == true)
        }
    }
}
