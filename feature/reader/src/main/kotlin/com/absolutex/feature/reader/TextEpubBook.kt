package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.source.epub.EpubBook
import com.absolutex.source.epub.EpubPackage
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
    private val entries: ArchiveEntries,
) {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val byLowerName = entries.names.associateBy { it.lowercase() }

    /** An entry's bytes, matched case-insensitively as EPUB paths are in practice. */
    fun read(name: String): ByteArray? {
        val entry = byLowerName[name.lowercase()] ?: return null
        cache[entry]?.let { return it }
        return entries.read(entry)?.also { cache[entry] = it }
    }

    internal companion object {
        /** Opens [uri] as a text EPUB, or null when it is not one. Blocking: call off the main thread. */
        fun open(context: Context, uri: Uri): TextEpubBook? {
            val entries = ArchiveEntries.list { context.openDescriptor(uri) } ?: return null
            val book = EpubPackage.parse(entries.names) { entries.read(it) } as? EpubBook.Reflowable ?: return null
            return TextEpubBook(book.spine, context.identityOf(uri), entries)
        }
    }
}

/** Opens a text EPUB off the main thread and remembers which chapter the reader is in. */
@HiltViewModel
class TextEpubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progress: ProgressDao,
) : ViewModel() {

    /** The book and the chapter to resume at, or null when it could not be opened. */
    suspend fun open(uri: Uri): Pair<TextEpubBook, Int>? = withContext(Dispatchers.IO) {
        val book = runCatching { TextEpubBook.open(context, uri) }.getOrNull() ?: return@withContext null
        val resume = progress.get(book.identity)?.pageIndex ?: 0
        book to resume.coerceIn(0, (book.spine.size - 1).coerceAtLeast(0))
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
