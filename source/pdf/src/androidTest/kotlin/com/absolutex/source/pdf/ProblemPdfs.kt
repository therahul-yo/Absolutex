package com.absolutex.source.pdf

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Problem PDFs for the open-failure corpus, built in memory like [MinimalPdf] so the
 * instrumented tests carry no binary fixtures.
 *
 * Every fixture below is a real file PDFium must accept or refuse on its own terms: the point
 * of the corpus is what the JNI layer does with hostile-but-plausible input (encrypted,
 * linearized, xref-broken, junk-prefixed, truncated), not what a fixture checked in years ago
 * happened to contain.
 */
internal object ProblemPdfs {

    /** User password every encrypted fixture shares. */
    const val USER_PASSWORD = "secret"

    /**
     * A V=1/R=2 RC4 user-password PDF, hand-rolled per PDF 32000 §7.6 so the test knows the
     * password independently of PDFium: the file key is MD5(padded user password + O + P +
     * ID) truncated to 5 bytes, O is the padded user password RC4'd under its own MD5-derived
     * key (no separate owner password exists, so the user password substitutes as the owner
     * one), U is the padding RC4'd under the file key, and the content stream is RC4'd under
     * the per-object key for `4 0 R`. Opens with [USER_PASSWORD], throws
     * `PdfPasswordException` without it.
     */
    fun encrypted(userPassword: String = USER_PASSWORD): ByteArray {
        val id = "absolutex-test-1".toByteArray(Charsets.US_ASCII)
        val userPad = padPassword(userPassword)
        // No separate owner password, so the spec substitutes the user password as the owner
        // one — O must be derived from it, not from an empty password, to stay conformant.
        val ownerKey = md5(userPad).copyOf(KEY_BYTES)
        val oEntry = rc4(ownerKey, userPad)
        val fileKey = md5(userPad, oEntry, PERMISSIONS_BYTES, id).copyOf(KEY_BYTES)
        val uEntry = rc4(fileKey, PADDING)
        val objectKey = md5(fileKey, byteArrayOf(4, 0, 0, 0, 0)).copyOf(KEY_BYTES + 5)
        val stream = rc4(objectKey, plainContent())
        val encrypt = "<< /Filter /Standard /V 1 /R 2 /Length 40 " +
            "/O ${hex(oEntry)} /U ${hex(uEntry)} /P $PERMISSIONS >>"
        return assemble(
            bodies = pageBodies(stream),
            trailerExtra = " /Encrypt $encrypt /ID [ ${hex(id)} ${hex(id)} ]",
        )
    }

    /**
     * A single-page linearized PDF: the parameter dict first, then every object (a one-page
     * book has no second section to stream), then the first-page xref, then the main xref the
     * trailer's `/Prev` points back to. PDFium honours the linear path, so a hand-waved hint
     * table would fail here rather than being silently ignored.
     */
    fun linearized(): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        write("%PDF-1.4\n")
        val dictAt = out.size()
        // Fixed-width numbers, patched once the offsets are known: rewriting digits in place
        // keeps every recorded offset byte-accurate (see MinimalPdf for why that matters).
        write("1 0 obj\n<< /Linearized 1 /L ")
        val lAt = out.size()
        write("0000000000 /H [0 0] /O 4 /E ")
        val eAt = out.size()
        write("0000000000 /N 1 /T ")
        val tAt = out.size()
        write("0000000000 >>\nendobj\n")
        val rest = listOf(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 3 0 R " +
                "/MediaBox [0 0 ${MinimalPdf.WIDTH_PT.toInt()} ${MinimalPdf.HEIGHT_PT.toInt()}] " +
                "/Contents 5 0 R /Resources << >> >>",
        )
        val offsets = IntArray(OBJECT_COUNT + 1)
        offsets[1] = dictAt
        rest.forEachIndexed { i, body ->
            offsets[i + 2] = out.size()
            write("${i + 2} 0 obj\n$body\nendobj\n")
        }
        offsets[OBJECT_COUNT] = out.size()
        val stream = plainContent()
        write("5 0 obj\n<< /Length ${stream.size} >>\nstream\n")
        out.write(stream)
        write("endstream\nendobj\n")
        val firstXref = out.size()
        writeXref(out, offsets)
        write("trailer\n<< /Size 6 /Root 2 0 R >>\nstartxref\n$firstXref\n")
        val mainXref = out.size()
        writeXref(out, offsets)
        write("trailer\n<< /Size 6 /Root 2 0 R /Prev $firstXref >>\nstartxref\n$mainXref\n%%EOF\n")
        return patchNumbers(out.toByteArray(), lAt to out.size(), eAt to mainXref, tAt to mainXref)
    }

    /**
     * A valid file whose xref offsets all point a few bytes past their objects. PDFium's
     * custom-document load rebuilds the xref by scanning (see `FPDF_LoadCustomDocument` in
     * pdfium_jni.c), so this must still open — it is the shape every damaged download takes.
     */
    fun brokenXref(): ByteArray = assemble(bodies = pageBodies(plainContent()), offsetDelta = 7)

    /**
     * A valid file behind a mail-style preamble. The spec tolerates up to 1024 junk bytes and
     * PDFium searches for the header; the sniffer routes the same window to the PDF open (see
     * `FormatSniffer.isPdf`), so this must never land in the archive reader.
     */
    fun leadingJunk(): ByteArray {
        val junk = "X-Archive: absolutex-test-mail\r\nContent-Type: application/pdf\r\n\r\n" +
            "JUNK".repeat(60) + "\r\n"
        return assemble(
            bodies = pageBodies(plainContent()),
            prefix = junk.toByteArray(Charsets.US_ASCII),
        )
    }

    /** A file cut in half: no xref, no trailer. Must throw `PdfException`, never crash. */
    fun truncated(): ByteArray {
        val whole = assemble(bodies = pageBodies(plainContent()))
        return whole.copyOf(whole.size / 2)
    }

    private fun assemble(
        bodies: List<ByteArray>,
        trailerExtra: String = "",
        offsetDelta: Int = 0,
        prefix: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(prefix)
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(bodies.size + 1)
        bodies.forEachIndexed { i, body ->
            offsets[i + 1] = out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xrefOffset = out.size()
        writeXref(out, offsets, offsetDelta)
        out.write(
            (
                "trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R$trailerExtra >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n"
            ).toByteArray(Charsets.US_ASCII),
        )
        return out.toByteArray()
    }

    private fun writeXref(out: ByteArrayOutputStream, offsets: IntArray, delta: Int = 0) {
        fun write(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        write("xref\n0 ${offsets.size}\n")
        write("0000000000 65535 f \n")
        for (i in 1 until offsets.size) {
            write("%010d 00000 n \n".format(offsets[i] + delta))
        }
    }

    private fun pageBodies(stream: ByteArray): List<ByteArray> {
        fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)
        return listOf(
            bytes("<< /Type /Catalog /Pages 2 0 R >>"),
            bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            bytes(
                "<< /Type /Page /Parent 2 0 R " +
                    "/MediaBox [0 0 ${MinimalPdf.WIDTH_PT.toInt()} ${MinimalPdf.HEIGHT_PT.toInt()}] " +
                    "/Contents 4 0 R /Resources << >> >>",
            ),
            bytes("<< /Length ${stream.size} >>\nstream\n") + stream + bytes("endstream"),
        )
    }

    private fun plainContent(): ByteArray =
        (
            "1 0 0 rg\n${MinimalPdf.RECT_X} ${MinimalPdf.RECT_Y} " +
                "${MinimalPdf.RECT_W} ${MinimalPdf.RECT_H} re\nf\n"
        ).toByteArray(Charsets.US_ASCII)

    private fun patchNumbers(bytes: ByteArray, vararg patches: Pair<Int, Int>): ByteArray {
        for ((at, value) in patches) {
            "%010d".format(value).toByteArray(Charsets.US_ASCII).copyInto(bytes, at)
        }
        return bytes
    }

    /**
     * Pads a password to 32 bytes per PDF 32000 §7.6.3.3 step (a): the raw bytes followed by the
     * *first* 32-n bytes of the padding string. `copyOf(n)` takes exactly those — reaching for
     * `copyOfRange(n, ...)` instead (skipping the first n padding bytes) derives a wrong key for
     * every short password and reads back as a wrong password on open.
     */
    private fun padPassword(password: String): ByteArray {
        val raw = password.toByteArray(Charsets.ISO_8859_1)
        if (raw.size >= PADDING.size) return raw.copyOf(PADDING.size)
        return raw + PADDING.copyOf(PADDING.size - raw.size)
    }

    private fun md5(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val state = IntArray(STATE_SIZE) { it }
        var j = 0
        for (i in 0 until STATE_SIZE) {
            j = (j + state[i] + (key[i % key.size].toInt() and BYTE_MASK)) and BYTE_MASK
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (k in data.indices) {
            i = (i + 1) and BYTE_MASK
            j = (j + state[i]) and BYTE_MASK
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
            out[k] = (data[k].toInt() xor state[(state[i] + state[j]) and BYTE_MASK]).toByte()
        }
        return out
    }

    /**
     * Hex string for a PDF trailer entry. The `and 0xFF` is load-bearing: `"%02X"` on a Kotlin
     * [Byte] sign-extends (0xFF becomes "FFFFFFFF", not "FF"), which silently corrupts every
     * key byte past 0x7F and reads back as a wrong password.
     */
    private fun hex(bytes: ByteArray): String =
        "<${bytes.joinToString("") { "%02X".format(it.toInt() and BYTE_MASK) }}>"

    /** 40-bit key: 5 bytes (PDF 32000 §7.6.2, the default `/Length 40`). */
    private const val KEY_BYTES = 5
    /** Permissions denied nothing the reader needs; the file still requires its password. */
    private const val PERMISSIONS = -4
    private const val OBJECT_COUNT = 5
    private const val STATE_SIZE = 256
    private const val BYTE_MASK = 0xFF
    private val PERMISSIONS_BYTES =
        byteArrayOf(0xFC.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    /**
     * The 32-byte password padding of PDF 32000 §7.6.3.3 step (a). The tail is `64 53 69 7A`:
     * read out of this repo's own libpdfium.so (.rodata, the single 32-byte occurrence of the
     * `28 BF 4E` prefix), and pypdf derives identical keys with it. A wrong tail derives a
     * wrong file key for every password and reads back as a wrong password on open.
     */
    private val PADDING = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )
}
