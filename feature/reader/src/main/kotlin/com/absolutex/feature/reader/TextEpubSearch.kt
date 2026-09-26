package com.absolutex.feature.reader

import com.absolutex.source.epub.EpubSearch
import com.absolutex.source.epub.SearchMatch
import com.absolutex.source.epub.SearchPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.zip.ZipInputStream

/** One hit: the chapter it is in, and the match (its occurrence in that chapter, its snippet). */
internal data class TextSearchHit(val chapter: Int, val match: SearchMatch)

/**
 * A search's progress: the hits so far in reading order, how many chapters have been read, and
 * whether it has finished. [pattern] is the regex source the page highlights the hit with.
 */
internal data class TextSearchState(
    val hits: List<TextSearchHit>,
    val searched: Int,
    val chapters: Int,
    val done: Boolean,
    val pattern: String,
) {
    /** Stopped at [MAX_HITS]: the list is the first hits in the book, not all of them. */
    val capped: Boolean get() = hits.size >= MAX_HITS
}

/**
 * Searches every chapter for [query], reporting as it goes; null-free and empty for a query too
 * short to search.
 *
 * One front-to-back pass over the ZIP reads each chapter once, and only its hits are kept: memory
 * is one chapter plus the hits, however long the book. Chapters the stream could not reach (a ZIP
 * it cannot walk) are read one by one afterwards. Cancelling the collector stops the search at the
 * next chapter, which is what a new keystroke does.
 */
internal fun TextEpubBook.search(query: String): Flow<TextSearchState> = flow {
    val pattern = EpubSearch.pattern(query) ?: return@flow
    val run = SearchRun(this@search, pattern)
    openZip()?.use { zip ->
        while (!run.finished) {
            val (entry, bytes) = nextChapter(zip, run.left) ?: break
            run.take(entry, bytes)?.let { emit(it) }
        }
    }
    for (entry in spine.filter { it in run.left }) {
        if (run.finished) break
        val bytes = peek(entry)
        if (bytes == null) run.skip(entry) else run.take(entry, bytes)?.let { emit(it) }
    }
    emit(run.state(done = true))
}.flowOn(Dispatchers.IO)

/** One search's bookkeeping: what is left to read, and the hits found so far in reading order. */
private class SearchRun(private val book: TextEpubBook, private val pattern: SearchPattern) {
    private val chapterOf = book.spine.withIndex().associate { (index, entry) -> entry to index }
    private val found = sortedMapOf<Int, List<SearchMatch>>()
    val left = book.spine.toMutableSet()
    private var total = 0
    private var sinceReport = 0

    val finished: Boolean get() = left.isEmpty() || total >= MAX_HITS

    /** Searches one chapter; the progress to report, when it is time to report it. */
    fun take(entry: String, bytes: ByteArray): TextSearchState? {
        left -= entry
        val matches = EpubSearch.matches(EpubSearch.textOf(bytes), pattern, MAX_HITS - total)
        if (matches.isNotEmpty()) {
            found[chapterOf.getValue(entry)] = matches
            total += matches.size
        }
        sinceReport++
        return if (matches.isNotEmpty() || sinceReport >= REPORT_EVERY) state(done = false) else null
    }

    /** A chapter that could not be read counts as searched: it has nothing to find. */
    fun skip(entry: String) {
        left -= entry
    }

    fun state(done: Boolean): TextSearchState {
        sinceReport = 0
        val hits = found.flatMap { (chapter, matches) -> matches.map { TextSearchHit(chapter, it) } }
        return TextSearchState(hits, book.spine.size - left.size, book.spine.size, done, pattern.source)
    }
}

/** The next entry in [zip] that is one of [wanted], with its bytes; null at the end or on a bad ZIP. */
private fun nextChapter(zip: ZipInputStream, wanted: Set<String>): Pair<String, ByteArray>? {
    while (true) {
        val entry = runCatching { zip.nextEntry }.getOrNull() ?: return null
        if (entry.name !in wanted) continue
        val bytes = runCatching { zip.readNBytes(MAX_CHAPTER_BYTES) }.getOrNull() ?: return null
        return entry.name to bytes
    }
}

/** Past this a list is not read, and a query that common is better narrowed. */
internal const val MAX_HITS = 1_000

/** Progress is reported at least this often, in chapters, while nothing matches. */
private const val REPORT_EVERY = 64

/** A chapter larger than this is read only this far: no prose chapter is 8 MiB. */
private const val MAX_CHAPTER_BYTES = 8 * 1024 * 1024
