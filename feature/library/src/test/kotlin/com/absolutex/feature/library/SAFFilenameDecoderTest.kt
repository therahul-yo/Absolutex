package com.absolutex.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SAFFilenameDecoderTest {

    @Test
    fun `file path returns file name`() {
        assertEquals("Batman 001.cbz", decodedFilename("/comics/Batman 001.cbz"))
    }

    @Test
    fun `SAF document id with nested folder`() {
        val encoded = "content://downloads/document/primary%3AComics%2FBatman%20001.cbz"
        assertEquals("Batman 001.cbz", decodedFilename(encoded))
    }

    @Test
    fun `SAF document id at root`() {
        val encoded = "content://downloads/document/primary%3ABatman%20001.cbz"
        assertEquals("Batman 001.cbz", decodedFilename(encoded))
    }

    @Test
    fun `percent-encoded spaces and colons`() {
        val encoded = "content://downloads/document/MSF%3ADownloads%2FAbsolute%20Batman%20%23001.cbz"
        assertEquals("Absolute Batman #001.cbz", decodedFilename(encoded))
    }

    @Test
    fun `plain SAF path without encoding`() {
        assertEquals("001.cbz", decodedFilename("content://downloads/document/primary:001.cbz"))
    }

    @Test
    fun `fallback to file name when nothing decodes`() {
        assertEquals("unknown", decodedFilename("unknown"))
    }
}
