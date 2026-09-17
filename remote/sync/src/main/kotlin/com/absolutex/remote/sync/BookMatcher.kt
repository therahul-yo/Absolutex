package com.absolutex.remote.sync

import com.absolutex.model.BookIdentity

/**
 * Maps an Absolutex book to its server twin by [BookIdentity] (display file name plus size).
 *
 * A book that matches nothing, matches more than one server book, or matches a record without
 * a size is left alone and never guessed — syncing to the wrong book is worse than not
 * syncing. Matching is exact (case-sensitive, byte-equal size): server and local names both
 * come from the same filesystem, so anything fuzzier would be inventing intent.
 */
// Komga read-progress pages count from 1 (verified: "page 1 is the first page",
// markBookReadProgress docs). Local page indexes count from 0.
const val KOMGA_FIRST_PAGE = 1

// Kavita pageNum counts from 0 like the local index (verified against Kavita's source:
// ReaderController.GetImage clamps `page < 0` to 0 and indexes the page cache with it, the
// 0-based convention — the lead still confirms push-N-shows-N live on the device checklist).
fun komgaPageToIndex(page: Int): Int = (page - KOMGA_FIRST_PAGE).coerceAtLeast(0)

fun komgaIndexToPage(pageIndex: Int): Int = pageIndex + KOMGA_FIRST_PAGE

fun kavitaPageToIndex(pageNum: Int): Int = pageNum.coerceAtLeast(0)

/** A Komga book matched to a local identity, or null when zero-or-many matched. */
fun matchKomgaBook(identity: String, books: List<BookRef>): BookRef? {
    val hits = books.filter { book ->
        val size = book.sizeBytes ?: return@filter false
        BookIdentity.of(book.name, size) == identity
    }
    return hits.singleOrNull()
}

/** A Kavita chapter file matched to a local identity, or null when zero-or-many matched. */
fun matchKavitaFile(
    identity: String,
    chapters: List<KavitaChapterFiles>,
): Pair<KavitaChapterFiles, KavitaFileRef>? {
    val hits = chapters.flatMap { chapter -> chapter.files.map { file -> chapter to file } }
        .filter { (_, file) -> BookIdentity.of(file.fileName, file.bytes) == identity }
    return hits.singleOrNull()
}
