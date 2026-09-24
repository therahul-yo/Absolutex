package com.absolutex.source.epub

import com.absolutex.model.ReadingFlow

/**
 * What an EPUB turned out to be once its package document was read.
 *
 * The three cases are genuinely different outcomes for the reader, not shades of failure: one
 * opens, one is a different product, and one is a broken file.
 */
sealed interface EpubBook {

    /**
     * A fixed-layout comic: every spine document resolved to exactly one image, in spine order.
     *
     * @param readingFlow [ReadingFlow.RTL] when the package declares
     *   `page-progression-direction="rtl"`, null when it declares nothing — the reader's own
     *   default then applies, rather than this guessing left-to-right on the book's behalf.
     */
    data class Pages(
        val entryNames: List<String>,
        val readingFlow: ReadingFlow?,
        val coverEntryName: String?,
    ) : EpubBook

    /**
     * A reflowable text EPUB: a spine of documents that carry no page image.
     *
     * Not a failure and not a malformed file — a novel is simply a different product from a comic
     * reader. It carries its spine so the text reader can lay the book out chapter by chapter.
     */
    data class Reflowable(
        /** The spine: the book's documents, in reading order, as archive entry names. */
        val spine: List<String>,
        /** The cover image's entry name, when the package declares one. */
        val coverEntryName: String?,
    ) : EpubBook

    /** No container.xml, no package document, or neither could be read. */
    data object Malformed : EpubBook
}
