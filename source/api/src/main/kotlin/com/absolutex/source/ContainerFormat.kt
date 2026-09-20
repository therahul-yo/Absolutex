package com.absolutex.source

/**
 * What a book's bytes say it is, as opposed to what its name claims (§2, §6).
 *
 * The distinction is not academic: a `.cbr` that is really a ZIP is the most common malformation
 * in a real library, and a SAF document's name is whatever the provider chose to report — so the
 * extension is a hint and the header is the answer.
 */
enum class ContainerFormat {
    ZIP,

    /** A ZIP that declares itself an EPUB in its first entry — see [FormatSniffer]. */
    EPUB,

    RAR4,
    RAR5,
    SEVEN_ZIP,
    TAR,
    PDF,

    /**
     * A folder of loose images treated as one book (§2).
     *
     * [FormatSniffer] never returns this and cannot: a directory has no header to read. The
     * caller decides it before opening anything — `File.isDirectory`, or a SAF document whose
     * MIME type is `vnd.android.document/directory`. This module is plain Kotlin/JVM, so that
     * check cannot live here.
     */
    DIRECTORY,

    /** Nothing recognised. Still worth handing to libarchive, which reads more than this lists. */
    UNKNOWN,
}
