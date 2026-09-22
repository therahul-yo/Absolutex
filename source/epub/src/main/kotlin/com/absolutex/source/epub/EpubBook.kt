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
     * reader. It is its own case so the reader can say so, instead of opening a book with no pages
     * and leaving the user looking at nothing.
     */
    data object Reflowable : EpubBook

    /** No container.xml, no package document, or neither could be read. */
    data object Malformed : EpubBook
}
