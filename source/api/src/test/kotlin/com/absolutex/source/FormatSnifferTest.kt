package com.absolutex.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §6: the format is decided by content, never by the name.
 *
 * ZIP and EPUB fixtures are built with `java.util.zip` rather than hand-assembled, so what is
 * asserted is a real archive's real header — a hand-rolled one could agree with a hand-rolled
 * parser while both disagree with every zipper in the world. The formats with no JDK writer
 * (RAR, 7z, TAR) are built from their published signatures, which is all a sniffer reads.
 */
class FormatSnifferTest {

    // ---- the happy path, one per format ------------------------------------------------

    @Test fun `zip is zip`() {
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(zip("page001.png" to PIXEL)))
    }

    @Test fun `empty zip is still a zip`() {
        // No entries at all: the file is just an end-of-central-directory record.
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(zip()))
    }

    @Test fun `spanning marker is a zip`() {
        val spanned = byteArrayOf(0x50, 0x4B, 0x07, 0x08) + zip("page001.png" to PIXEL)
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(spanned))
    }

    @Test fun `conforming epub is an epub`() {
        assertEquals(ContainerFormat.EPUB, FormatSniffer.detect(epub()))
    }

    @Test fun `an epub zipped with META-INF first is still an epub`() {
        // Converter-made EPUBs (web novels) often skip OCF's mimetype-first rule. Sniffed as a
        // plain ZIP they opened as bundles of images.
        val loose = zip(
            "META-INF/container.xml" to ascii("<container/>"),
            "mimetype" to ascii("application/epub+zip"),
            "OEBPS/content.opf" to ascii("<package/>"),
        )
        assertEquals(ContainerFormat.EPUB, FormatSniffer.detect(loose.copyOf(FormatSniffer.HEADER_BYTES)))
    }

    @Test fun `a comic zip is not mistaken for a loose epub`() {
        val comic = zip("001.jpg" to ByteArray(8), "META-INF/container.xml" to ascii("<container/>"))
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(comic.copyOf(FormatSniffer.HEADER_BYTES)))
    }

    @Test fun `rar4 and rar5 are told apart`() {
        assertEquals(ContainerFormat.RAR4, FormatSniffer.detect(signature(RAR4)))
        assertEquals(ContainerFormat.RAR5, FormatSniffer.detect(signature(RAR5)))
    }

    @Test fun `seven zip is seven zip`() {
        assertEquals(ContainerFormat.SEVEN_ZIP, FormatSniffer.detect(signature(SEVEN_ZIP)))
    }

    @Test fun `posix and gnu tar both match`() {
        assertEquals(ContainerFormat.TAR, FormatSniffer.detect(tar("ustar\u0000" + "00")))
        assertEquals(ContainerFormat.TAR, FormatSniffer.detect(tar("ustar  \u0000")))
    }

    @Test fun `pdf is pdf`() {
        assertEquals(ContainerFormat.PDF, FormatSniffer.detect(ascii("%PDF-1.7\n%âãÏÓ\n")))
    }

    // ---- the whole point: the name is not the format -----------------------------------

    @Test fun `a zip wearing a cbr suffix is a zip`() {
        // Corpus case 06_zip_named_cbr.cbr. The extension says RAR; the bytes say ZIP, and the
        // bytes win — otherwise the reader hands a ZIP to the RAR reader and the book "breaks".
        val bytes = zip("page001.png" to PIXEL)
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(bytes))
    }

    @Test fun `a zip containing the pdf magic is not a pdf`() {
        // The regression this ordering exists for. A CBZ that stores a PDF, or merely names an
        // entry "%PDF-notes.txt", carries those five bytes inside its first kibibyte. Searching
        // for the PDF magic before checking the ZIP signature classifies it as a PDF, and the
        // book fails to open. Structured signatures are therefore checked first.
        val bytes = zip("%PDF-notes.txt" to ascii("%PDF-1.7 not really a pdf"))
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(bytes))
    }

    @Test fun `a tar whose first entry is a pdf is a tar`() {
        // The reason TAR is checked before PDF and not after. A .cbt carrying a bonus PDF puts
        // "%PDF-" at offset 512, the first byte after the header block — comfortably inside the
        // kibibyte the PDF scan covers. Checked the other way round, the archive opens as a PDF.
        val bytes = tar("ustar\u0000" + "00") + ascii("%PDF-1.7\n")
        assertEquals(ContainerFormat.TAR, FormatSniffer.detect(bytes))
    }

    @Test fun `junk before the pdf header is still a pdf`() {
        val bytes = ascii("Content-Type: application/pdf\r\n\r\n") + ascii("%PDF-1.7\n")
        assertEquals(ContainerFormat.PDF, FormatSniffer.detect(bytes))
    }

    @Test fun `pdf magic at the very edge of the header is found`() {
        val at = FormatSniffer.HEADER_BYTES - 5
        val bytes = ByteArray(FormatSniffer.HEADER_BYTES) { ' '.code.toByte() }
        ascii("%PDF-").copyInto(bytes, at)
        assertEquals(ContainerFormat.PDF, FormatSniffer.detect(bytes))
    }

    @Test fun `pdf magic beyond the header is not found`() {
        // Not a defect: the spec's own tolerance stops at 1024 bytes, and reading further would
        // cost every open a bigger read to serve a file no other reader accepts either.
        val bytes = ByteArray(FormatSniffer.HEADER_BYTES) { ' '.code.toByte() } + ascii("%PDF-1.7")
        assertEquals(ContainerFormat.UNKNOWN, FormatSniffer.detect(bytes))
    }

    // ---- EPUB is a claim a file has to actually make ------------------------------------

    @Test fun `zip whose first entry is not mimetype is a plain zip`() {
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(epub(name = "mimetypes")))
    }

    @Test fun `deflated mimetype is not a conforming epub`() {
        // The payload is not literal bytes any more, so the sniffer cannot honestly read it.
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(epub(stored = false)))
    }

    @Test fun `a deflate-flagged mimetype with literal bytes is not an epub`() {
        // Hand-assembled, because no zipper produces it — which is the point. The entry declares
        // DEFLATE, so the bytes at the payload offset are a compressed stream and mean nothing
        // literally; a file that puts "application/epub+zip" there anyway is making a claim its
        // own header contradicts. Only the compression method makes those bytes trustworthy, so
        // dropping that check is what this pins.
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(deflateFlaggedMimetype()))
    }

    @Test fun `mimetype naming another format is not an epub`() {
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(epub(mimetype = "application/zip")))
    }

    @Test fun `mimetype behind an extra field is still an epub`() {
        // OCF forbids the extra field; zippers that pad for alignment write one anyway, and the
        // payload moves by exactly its length. Reading that length is what keeps this working.
        assertEquals(ContainerFormat.EPUB, FormatSniffer.detect(epub(extra = ByteArray(12))))
    }

    @Test fun `epub magic is not matched inside a later entry`() {
        // "application/epub+zip" appearing in a CBZ's stored text entry must not promote it.
        val bytes = zip("notes.txt" to ascii("application/epub+zip"))
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(bytes))
    }

    // ---- hostile and degenerate input --------------------------------------------------

    @Test fun `empty header is unknown, not a crash`() {
        assertEquals(ContainerFormat.UNKNOWN, FormatSniffer.detect(ByteArray(0)))
    }

    @Test fun `a header shorter than any signature is unknown`() {
        assertEquals(ContainerFormat.UNKNOWN, FormatSniffer.detect(byteArrayOf(0x50, 0x4B, 0x03)))
    }

    @Test fun `a zip truncated inside its local header is still a zip`() {
        // 20 bytes: the signature is there, the name-length field is not. Reading it must not
        // throw — a truncated book is corpus case 11, not an exceptional case.
        val bytes = zip("page001.png" to PIXEL).copyOf(20)
        assertEquals(ContainerFormat.ZIP, FormatSniffer.detect(bytes))
    }

    @Test fun `a buffer that ends inside the tar magic is unknown`() {
        assertEquals(ContainerFormat.UNKNOWN, FormatSniffer.detect(tar("ustar\u0000" + "00").copyOf(260)))
    }

    @Test fun `every signature survives being handed one byte at a time`() {
        // A provider is free to return fewer bytes than asked for. Every prefix of every fixture
        // must answer something, and never throw.
        for (fixture in listOf(zip("p.png" to PIXEL), epub(), signature(RAR5), tar("ustar\u0000" + "00"))) {
            for (length in 0..fixture.size) {
                FormatSniffer.detect(fixture.copyOf(length))
            }
        }
    }

    @Test fun `detect never returns DIRECTORY`() {
        // A folder has no header, so no byte sequence may produce it — the caller decides that
        // case before a descriptor is ever opened.
        val fixtures = listOf(
            zip("p.png" to PIXEL), epub(), signature(RAR4), signature(RAR5),
            signature(SEVEN_ZIP), tar("ustar\u0000" + "00"), ascii("%PDF-1.7"), ByteArray(0),
        )
        for (fixture in fixtures) {
            // assertNotEquals, not Kotlin's assert(): that one compiles to a no-op unless the
            // JVM is started with -ea, so it would pass here whatever the sniffer returned.
            assertNotEquals(ContainerFormat.DIRECTORY, FormatSniffer.detect(fixture))
        }
    }

    // ---- fixtures ----------------------------------------------------------------------

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A real OCF-shaped EPUB opening, with each rule it depends on individually defeatable. */
    private fun epub(
        name: String = "mimetype",
        mimetype: String = "application/epub+zip",
        stored: Boolean = true,
        extra: ByteArray? = null,
    ): ByteArray {
        val payload = ascii(mimetype)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            val entry = ZipEntry(name)
            if (stored) {
                // STORED demands the sizes and CRC up front, which is also why a conforming
                // EPUB's payload lands at a computable offset.
                entry.method = ZipEntry.STORED
                entry.size = payload.size.toLong()
                entry.compressedSize = payload.size.toLong()
                entry.crc = CRC32().apply { update(payload) }.value
            }
            extra?.let { entry.extra = it }
            zos.putNextEntry(entry)
            zos.write(payload)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/page001.xhtml"))
            zos.write(ascii("<html/>"))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * A ZIP local file header declaring DEFLATE over a `mimetype` entry, followed by the EPUB
     * mimetype as literal bytes. Assembled by hand (APPNOTE 4.3.7 field order) since a correct
     * zipper cannot emit this contradiction.
     */
    private fun deflateFlaggedMimetype(): ByteArray {
        val name = ascii("mimetype")
        val payload = ascii("application/epub+zip")
        val header = ByteArray(30)
        byteArrayOf(0x50, 0x4B, 0x03, 0x04).copyInto(header, 0)
        u16Into(header, 4, 20)                 // version needed
        u16Into(header, 8, 8)                  // compression method: DEFLATE, not STORED
        u16Into(header, 18, payload.size)      // compressed size
        u16Into(header, 22, payload.size)      // uncompressed size
        u16Into(header, 26, name.size)
        u16Into(header, 28, 0)                 // no extra field
        return header + name + payload
    }

    private fun u16Into(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    /** A signature followed by plausible filler — all a sniffer of these formats ever reads. */
    private fun signature(magic: ByteArray): ByteArray = magic + ByteArray(64) { 0x7F }

    /** A 512-byte TAR header block carrying [magic] at offset 257. */
    private fun tar(magic: String): ByteArray {
        val block = ByteArray(512)
        ascii("page001.png").copyInto(block, 0)
        ascii(magic).copyInto(block, 257)
        return block
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private companion object {
        val PIXEL = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        val SEVEN_ZIP = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    }
}
