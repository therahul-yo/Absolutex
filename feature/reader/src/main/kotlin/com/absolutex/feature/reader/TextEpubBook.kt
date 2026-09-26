package com.absolutex.feature.reader

import com.absolutex.core.stats.PageSettled
import com.absolutex.core.data.ReadingHistory
import com.absolutex.core.data.TEXT_EPUB_FORMAT
import com.absolutex.core.data.BookFactsDao
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.util.zip.ZipInputStream
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.source.epub.EpubBook
import com.absolutex.source.epub.EpubPackage
import com.absolutex.source.epub.EpubToc
import com.absolutex.model.TocEntry
import com.absolutex.source.libarchive.ArchiveEntries
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * An open text EPUB: its chapters in reading order, and the bytes of any entry by name.
 *
 * Entries are read by ordinal through libarchive (the same path every CBZ page takes, SAF or file)
 * and kept once read: a chapter asks for its stylesheet and images on every load, and a text book
 * is small enough that holding what it has asked for costs little.
 */
class TextEpubBook internal constructor(
    val spine: List<String>,
    val identity: String,
    /** The cover image's entry, when the package declares one. */
    val coverEntryName: String?,
    private val entries: ArchiveEntries,
    /** The file from its start, for one sequential pass over every chapter (see [openZip]). */
    private val openStream: () -> InputStream,
) {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val byLowerName = entries.names.associateBy { it.lowercase() }

    /** The book's own contents, chapters as spine indices; empty when it declares none. */
    val toc: List<TocEntry> by lazy { EpubToc.parse(entries.names, spine, ::read) }

    /** An entry's bytes, matched case-insensitively as EPUB paths are in practice. */
    fun read(name: String): ByteArray? {
        val entry = byLowerName[name.lowercase()] ?: return null
        cache[entry]?.let { return it }
        return entries.read(entry)?.also { cache[entry] = it }
    }

    /**
     * An entry's bytes without keeping them: a search reads every chapter once, and caching 2,700
     * chapters would hold the whole book in memory for a single query.
     */
    fun peek(name: String): ByteArray? {
        val entry = byLowerName[name.lowercase()] ?: return null
        return cache[entry] ?: entries.read(entry)
    }

    /**
     * The file as a ZIP stream, or null when it cannot be opened. An EPUB is always a ZIP, and
     * reading it front to back is one pass over the file, where reading each chapter by ordinal
     * would walk the archive's headers again for every one of them.
     */
    fun openZip(): ZipInputStream? = runCatching { ZipInputStream(openStream().buffered()) }.getOrNull()

    internal companion object {
        /** Opens [uri] as a text EPUB, or null when it is not one. Blocking: call off the main thread. */
        fun open(context: Context, uri: Uri): TextEpubBook? {
            val entries = ArchiveEntries.list { context.openDescriptor(uri) } ?: return null
            val book = EpubPackage.parse(entries.names) { entries.read(it) } as? EpubBook.Reflowable ?: return null
            val stream = { ParcelFileDescriptor.AutoCloseInputStream(context.openDescriptor(uri)) }
            return TextEpubBook(book.spine, context.identityOf(uri), book.coverEntryName, entries, stream)
                // Parsed here, off the main thread, so the contents button never parses on a tap.
                .also { it.toc }
        }
    }
}

/** Where to reopen a text book: the chapter, how far through it, and whether it scrolls. */
data class TextResume(val chapter: Int, val fraction: Float, val scroll: Boolean)

/**
 * Opens a text EPUB off the main thread and remembers where the reader is.
 *
 * The chapter goes in the shared progress table, which the library reads for Recent and its
 * progress bars. How far through the chapter, and the pages/scroll choice, are this reader's own
 * and live in its preferences: a fraction rather than a page, so it survives a text-size change.
 */
@HiltViewModel
class TextEpubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progress: ProgressDao,
    private val facts: BookFactsDao,
    private val history: ReadingHistory,
) : ViewModel() {

    /** The book and the chapter to resume at, or null when it could not be opened. */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun open(uri: Uri): Pair<TextEpubBook, TextResume>? = withContext(Dispatchers.IO) {
        val book = runCatching { TextEpubBook.open(context, uri) }.getOrNull() ?: return@withContext null
        // Opening it proves it a text book: the library files it with the documents from now on.
        facts.updateFormat(if (uri.scheme == "file") uri.path.orEmpty() else uri.toString(), TEXT_EPUB_FORMAT)
        val chapter = (progress.get(book.identity)?.pageIndex ?: 0).coerceIn(0, (book.spine.size - 1).coerceAtLeast(0))
        val saved = prefs.getString(POSITION + book.identity, null)?.split('|')
        val fraction = saved?.takeIf { it.firstOrNull()?.toIntOrNull() == chapter }?.getOrNull(1)?.toFloatOrNull() ?: 0f
        book to TextResume(chapter, fraction.coerceIn(0f, 1f), prefs.getBoolean(SCROLL, false))
    }

    /** How far through [chapter] the reader is, as a fraction; saved as they read. */
    fun savePosition(book: TextEpubBook, chapter: Int, fraction: Float) {
        prefs.edit().putString(POSITION + book.identity, "$chapter|$fraction").apply()
    }

    /**
     * Logs a page read for stats. A text book's page is only meaningful within its chapter, so
     * the key spaces chapters apart: stats count distinct pages, and page 3 of two chapters are
     * two pages read.
     */
    fun recordPage(book: TextEpubBook, chapter: Int, page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = chapter * PAGES_PER_CHAPTER_KEY + page
            runCatching { history.record(listOf(PageSettled(book.identity, key, System.currentTimeMillis()))) }
        }
    }

    /** Pages or scroll, for every text book: a way of reading, not a property of one book. */
    fun saveScroll(scroll: Boolean) {
        prefs.edit().putBoolean(SCROLL, scroll).apply()
    }

    /**
     * Saves the chapter as the book's position. The library reads position rows as page N of M,
     * so a text book reports chapters: its progress bar and Recent then work unchanged.
     */
    fun saveChapter(book: TextEpubBook, chapter: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            progress.upsert(ReadingProgress(book.identity, chapter, book.spine.size, System.currentTimeMillis()))
        }
    }
}

private const val PREFS = "text_epub"
private const val SCROLL = "scroll"
private const val POSITION = "position:"
private const val PAGES_PER_CHAPTER_KEY = 10_000
