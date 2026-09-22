package com.absolutex.source.folder

import com.absolutex.model.Page
import com.absolutex.source.ComicSource
import com.absolutex.source.EntryFilter
import com.absolutex.source.NaturalOrder
import java.io.InputStream

/**
 * One file inside a folder book, as the caller found it.
 *
 * [open] is a factory rather than an open stream because a folder book's pages are read on
 * demand and possibly more than once: holding a stream per page would pin a descriptor for every
 * page in the book from the moment it opened.
 *
 * @param sizeBytes the file's length. Carried through to [Page.sizeBytes] so the page list and
 *   the book's identity sum are read off the same entries rather than computed twice — see
 *   [FolderComicSource.open].
 */
data class FolderEntry(
    val name: String,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

/**
 * A folder of loose images read as one book (§2, §6).
 *
 * The library has listed folder books since the scanner landed, but nothing could open one. This
 * is that reader, and it is deliberately the thinnest thing that works: a folder has no container
 * to parse, so all this type decides is *which* files are pages and *in what order*.
 *
 * Two rules the caller must not have to guess at:
 *
 * **Order is [NaturalOrder] over the file name**, so "2.jpg" precedes "10.jpg" — the ordering the
 * archive sources already use, applied to the same names. A folder's directory order is whatever
 * the filesystem or the provider felt like returning and is never reading order.
 *
 * **Which files count is the caller's decision, not this class's.** The caller hands in the
 * entries it already selected, because on both routes that selection is the same code that
 * computes the book's frozen identity sum (see `OpenBook.kt`) — and identity and page list
 * disagreeing is precisely the bug that would orphan a folder book's progress. All this class
 * adds is a defensive [EntryFilter.isPage] pass, so a caller that hands over a `.txt` gets it
 * dropped rather than turned into a page that can never decode.
 *
 * Thread safety comes free here, and for a reason worth stating: the archive sources need a fresh
 * descriptor per read because many pages share one container descriptor whose offset they would
 * corrupt (see `LibArchiveSource`). A folder book has no shared descriptor at all — every page is
 * its own file and every [openPage] opens its own stream — so concurrent reads are independent by
 * construction, not by discipline.
 */
class FolderComicSource private constructor(
    override val pages: List<Page>,
    /** Parallel to [pages]: sorting reorders both together. */
    private val opens: List<() -> InputStream>,
) : ComicSource {

    /**
     * @throws java.io.IOException if the file has gone since the folder was listed — a folder is
     *   live storage a user can edit while reading it, so that is ordinary, not exceptional.
     */
    override fun openPage(index: Int): InputStream {
        if (index !in pages.indices) throw IndexOutOfBoundsException("page $index of ${pages.size}")
        return opens[index]()
    }

    /** Owns nothing: each page opened its own stream and the caller closed it. */
    override fun close() = Unit

    companion object {
        /**
         * Builds the book from [entries], in reading order.
         *
         * An empty result is returned rather than refused. A folder whose images all vanished is
         * an empty book, and the guard for that is already central — `ThumbnailPipeline` and the
         * reader both reject a source with no pages — so throwing here would only add a second,
         * differently-worded failure for the same condition.
         */
        fun open(entries: List<FolderEntry>): FolderComicSource {
            val ordered = entries
                .filter { EntryFilter.isPage(it.name) }
                // Stable: two files whose names compare equal keep the order they arrived in.
                .sortedWith(compareBy(NaturalOrder) { it.name })
            val pages = ordered.mapIndexed { index, entry ->
                Page(index = index, entryName = entry.name, sizeBytes = entry.sizeBytes)
            }
            return FolderComicSource(pages, ordered.map { it.open })
        }
    }
}
