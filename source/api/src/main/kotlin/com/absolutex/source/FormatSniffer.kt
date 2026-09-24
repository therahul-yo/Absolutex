package com.absolutex.source

/**
 * Decides a container's [ContainerFormat] from its first bytes (§2, §6).
 *
 * Reads at most [HEADER_BYTES] and allocates nothing beyond the caller's buffer, so sniffing
 * costs one read of a kibibyte against the 250 ms open budget (§3) — and, being plain Kotlin,
 * every malformation below is a JVM test rather than something only a device can exercise.
 *
 * Nothing here is authoritative about *page* content: a format that sniffs as [ContainerFormat.ZIP]
 * may still be unreadable. The question this answers is only "which reader opens it".
 */
object FormatSniffer {

    /**
     * How many bytes [detect] wants. Fewer is safe — every read below is bounds-checked — but
     * costs accuracy for the formats that sign late.
     *
     * One kibibyte covers all of them: ZIP, RAR and 7z sign at offset 0, TAR's `ustar` sits at
     * 257, a conforming EPUB's `mimetype` payload ends by 58, and PDF permits up to 1024 bytes
     * of junk ahead of `%PDF-`.
     */
    const val HEADER_BYTES = 1024

    /**
     * @param header the container's leading bytes, at most [HEADER_BYTES] of them. A short or
     *   empty array is not an error: it yields [ContainerFormat.UNKNOWN] rather than throwing,
     *   because a zero-length book is a thing users really do have.
     */
    fun detect(header: ByteArray): ContainerFormat {
        // Fixed-offset signatures first, and the PDF search last, deliberately.
        //
        // "%PDF-" is matched by scanning rather than at a fixed offset (see [isPdf]), so a CBZ
        // that merely CONTAINS those five bytes in its first kibibyte — a stored PDF entry, an
        // entry named "%PDF-notes.txt" — would otherwise be opened as a PDF and fail. Checking
        // the structured magics first makes that impossible: no PDF begins "PK\u0003\u0004".
        if (isZip(header)) return zipFlavour(header)
        // RAR4 and RAR5 share six bytes and differ at the seventh, so neither prefixes the other.
        if (startsWith(header, RAR5_MAGIC)) return ContainerFormat.RAR5
        if (startsWith(header, RAR4_MAGIC)) return ContainerFormat.RAR4
        if (startsWith(header, SEVEN_ZIP_MAGIC)) return ContainerFormat.SEVEN_ZIP
        if (isTar(header)) return ContainerFormat.TAR
        if (isPdf(header)) return ContainerFormat.PDF
        return ContainerFormat.UNKNOWN
    }

    /**
     * Three openings are a ZIP: a local file header, an end-of-central-directory record (an
     * archive with no entries), and the spanning marker some writers emit ahead of the first
     * entry. Only the first can carry an EPUB's `mimetype`.
     */
    private fun isZip(header: ByteArray): Boolean =
        startsWith(header, ZIP_LOCAL) ||
            startsWith(header, ZIP_EMPTY) ||
            startsWith(header, ZIP_SPANNED)

    private fun zipFlavour(header: ByteArray): ContainerFormat =
        if (isEpub(header) || looksLikeLooseEpub(header)) ContainerFormat.EPUB else ContainerFormat.ZIP

    /**
     * A ZIP whose first entry is the package's own `META-INF/` is an EPUB its tool zipped without
     * OCF's ordering rule (mimetype first). Plenty of converters do, web-novel exporters
     * especially; read as a plain ZIP it opened as a bundle of images — a novel showing only its
     * cover and illustrations. A comic archive does not begin with `META-INF/`, so this costs a CBZ
     * nothing and is still decided from the header alone. (The reader confirms: a ZIP that is not
     * really an EPUB fails the package parse and falls back to the archive reader.)
     */
    private fun looksLikeLooseEpub(header: ByteArray): Boolean =
        startsWith(header, ZIP_LOCAL) && u16(header, NAME_LENGTH_AT) > META_INF.size &&
            matchesAt(header, LOCAL_HEADER_BYTES, META_INF)

    /**
     * An EPUB is a ZIP whose *first* entry is an uncompressed `mimetype` holding exactly
     * `application/epub+zip`. The OCF container specification mandates that shape for one
     * reason: so the format is identifiable from the leading bytes alone, which is exactly the
     * question here.
     *
     * A file that nests the entry, deflates it, or omits it is not a conforming EPUB and reads
     * here as [ContainerFormat.ZIP]. That is the right answer for a sniffer: it is still a ZIP,
     * it still opens, and confirming a non-conforming EPUB needs the entry list — which costs a
     * full open and belongs in the EPUB reader, not in a header check.
     */
    private fun isEpub(header: ByteArray): Boolean {
        if (!startsWith(header, ZIP_LOCAL)) return false
        if (u16(header, COMPRESSION_METHOD_AT) != STORED) return false
        val nameLength = u16(header, NAME_LENGTH_AT)
        val extraLength = u16(header, EXTRA_LENGTH_AT)
        if (nameLength != MIMETYPE_NAME.size || extraLength < 0) return false
        if (!matchesAt(header, LOCAL_HEADER_BYTES, MIMETYPE_NAME)) return false
        // The extra field's length is honoured rather than assumed zero: OCF forbids one, but a
        // zipper that pads for alignment writes it anyway and the payload moves by exactly this.
        return matchesAt(header, LOCAL_HEADER_BYTES + nameLength + extraLength, EPUB_MIMETYPE)
    }

    /**
     * `ustar` at offset 257 is all a TAR header declares about itself; POSIX follows it with
     * "\u000000" and GNU with "  \u0000", so matching the five shared bytes covers both.
     *
     * Pre-POSIX v7 tars carry no magic at all and land in [ContainerFormat.UNKNOWN]. Nothing is
     * lost by that: libarchive reads them anyway, and UNKNOWN is routed to libarchive.
     */
    private fun isTar(header: ByteArray): Boolean = matchesAt(header, TAR_MAGIC_AT, TAR_MAGIC)

    /**
     * A bounded search, not a magic at offset zero: the PDF specification tells readers to accept
     * up to 1024 bytes of junk before the header, and files with a mail or HTTP preamble really
     * do reach a reader. Matches the tolerance the reader's PDF path already had.
     */
    private fun isPdf(header: ByteArray): Boolean {
        val last = minOf(header.size, HEADER_BYTES) - PDF_MAGIC.size
        for (at in 0..last) {
            if (matchesAt(header, at, PDF_MAGIC)) return true
        }
        return false
    }

    /** True when [pattern] sits at [at]; false — never an exception — when it runs off the end. */
    private fun matchesAt(bytes: ByteArray, at: Int, pattern: ByteArray): Boolean {
        if (at < 0 || at > bytes.size - pattern.size) return false
        for (i in pattern.indices) {
            if (bytes[at + i] != pattern[i]) return false
        }
        return true
    }

    private fun startsWith(bytes: ByteArray, pattern: ByteArray): Boolean =
        matchesAt(bytes, 0, pattern)

    /** Little-endian uint16, or -1 when it does not fit — truncated headers are ordinary input. */
    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at > bytes.size - 2) return -1
        val low = bytes[at].toInt() and BYTE_MASK
        val high = bytes[at + 1].toInt() and BYTE_MASK
        return low or (high shl BITS_PER_BYTE)
    }

    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    private val RAR4_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val SEVEN_ZIP_MAGIC =
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val TAR_MAGIC = "ustar".toByteArray(Charsets.US_ASCII)
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val MIMETYPE_NAME = "mimetype".toByteArray(Charsets.US_ASCII)
    private val META_INF = "META-INF/".toByteArray(Charsets.US_ASCII)
    private val EPUB_MIMETYPE = "application/epub+zip".toByteArray(Charsets.US_ASCII)

    /** Offsets into a ZIP local file header (APPNOTE 4.3.7) and the TAR header block. */
    private const val COMPRESSION_METHOD_AT = 8
    private const val NAME_LENGTH_AT = 26
    private const val EXTRA_LENGTH_AT = 28
    private const val LOCAL_HEADER_BYTES = 30
    private const val TAR_MAGIC_AT = 257
    private const val STORED = 0
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
}
