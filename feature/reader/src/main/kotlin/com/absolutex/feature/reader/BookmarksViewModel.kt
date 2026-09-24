package com.absolutex.feature.reader

import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.Bookmark
import com.absolutex.core.data.BookmarkDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class BookmarksViewModel @Inject constructor(private val dao: BookmarkDao) : ViewModel() {

    // Two quick taps must land as on-then-off. Unserialised, both read "no bookmark" and both add.
    private val toggling = Mutex()

    fun pages(bookId: String): Flow<List<Int>> = dao.pages(bookId)

    fun toggle(bookId: String, page: Int) {
        viewModelScope.launch {
            toggling.withLock {
                if (dao.remove(bookId, page) == 0) dao.add(Bookmark(bookId, page, System.currentTimeMillis()))
            }
        }
    }
}

/** Where the slider's track starts and ends inside its own width: half of the default thumb. */
private val TRACK_INSET = 10.dp
private val MARK_RADIUS = 3.dp

/**
 * Bookmarks in the chrome (§5.2): a toggle for the page on screen, a dot on the progress bar per
 * bookmark, and a chip per bookmark to jump to it. Sits directly above the seek slider, so the dots
 * line up with the track.
 */
@Composable
internal fun BookmarkBar(
    bookId: String,
    page: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    vm: BookmarksViewModel = hiltViewModel(),
) {
    val marks by remember(bookId) { vm.pages(bookId) }.collectAsStateWithLifecycle(emptyList())
    val colour = MaterialTheme.colorScheme.primary
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(marks) { mark ->
                    AssistChip(
                        onClick = { onJump(mark) },
                        label = { Text(stringResource(R.string.reader_bookmark_page, mark + 1)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Bookmark, null, Modifier.size(AssistChipDefaults.IconSize))
                        },
                    )
                }
            }
            val toggleLabel = if (page in marks) R.string.reader_bookmark_remove else R.string.reader_bookmark_add
            val marked = page in marks
            IconToggleButton(checked = marked, onCheckedChange = { vm.toggle(bookId, page) }) {
                Icon(
                    if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = stringResource(toggleLabel),
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(MARK_RADIUS * 2).padding(horizontal = TRACK_INSET)) {
            val last = (pageCount - 1).coerceAtLeast(1)
            marks.forEach { mark ->
                drawCircle(colour, MARK_RADIUS.toPx(), Offset(size.width * mark / last, size.height / 2))
            }
        }
    }
}
