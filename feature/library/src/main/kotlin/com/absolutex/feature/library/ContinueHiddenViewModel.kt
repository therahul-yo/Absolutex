package com.absolutex.feature.library

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Books taken off the Continue reading strip, and when.
 *
 * A dismissal hides a book only until it is read again: [isShown] compares the book's last read
 * time with the moment it was hidden, so opening it later puts it back on the strip with no
 * undo to find. The reading position is never touched — hiding is a statement about the strip,
 * not about the book.
 */
@HiltViewModel
class ContinueHiddenViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(read())

    /** Path to the time it was hidden, in epoch milliseconds. */
    val hidden: StateFlow<Map<String, Long>> = _hidden.asStateFlow()

    fun hide(path: String, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(path, now).apply()
        _hidden.value = _hidden.value + (path to now)
    }

    private fun read(): Map<String, Long> =
        prefs.all.mapNotNull { (path, at) -> (at as? Long)?.let { path to it } }.toMap()
}

/** Whether [book] belongs on the strip: never hidden, or read again since it was. */
internal fun isShown(book: LibraryBookUi, hidden: Map<String, Long>): Boolean {
    val at = hidden[book.path] ?: return true
    return (book.lastReadAt ?: 0L) > at
}

private const val PREFS = "continue_hidden"
