package com.absolutex.feature.library

import com.absolutex.core.data.LibraryBook

/**
 * §5.1: the same file reached through two configured locations is one book.
 *
 * [LibraryBook.contentKey] is that identity — `BookIdentity.of(name, size)`, the key reading
 * progress is stored under too — so a book visible through both an SD card and a folder that
 * contains it appears once, keeps one reading position, and counts once towards a series shelf.
 *
 * The first occurrence wins, so the order the scanner emitted rows in (the DAO's series/issue
 * order) decides which path the library shows.
 */
internal fun List<LibraryBook>.deduplicatedByIdentity(): List<LibraryBook> =
    distinctBy { it.contentKey }
