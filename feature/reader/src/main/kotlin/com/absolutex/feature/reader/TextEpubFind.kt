package com.absolutex.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.Motion
import com.absolutex.model.TocEntry
import kotlinx.coroutines.delay

/**
 * The text reader's panel above its bottom bar: a search field over the book's contents. With no
 * query it is the contents, opened at the current chapter; typing searches the whole book, and a
 * result opens its chapter on the page of that match, highlighted.
 */
@Composable
internal fun FindAndContents(
    book: TextEpubBook,
    chapter: Int,
    open: Boolean,
    onChapter: (Int) -> Unit,
    onHit: (TextSearchHit, String) -> Unit,
) {
    AnimatedVisibility(open, enter = expandVertically(Motion.enter()), exit = shrinkVertically(Motion.exit())) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column {
                var query by rememberSaveable { mutableStateOf("") }
                SearchField(query, onChange = { query = it })
                // Restarts on every keystroke, cancelling the search before it: only the last
                // query's results ever arrive, after a short pause so typing is not a search each.
                val state by produceState<TextSearchState?>(null, query) {
                    value = null
                    delay(TYPING_PAUSE_MS)
                    book.search(query).collect { value = it }
                }
                val current = state
                when {
                    current != null -> Results(current, book.toc, onHit)
                    query.isBlank() && book.toc.isNotEmpty() ->
                        TocPanel(book.toc, onJump = onChapter, current = chapter)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.reader_text_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.reader_text_search_clear))
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Results(state: TextSearchState, toc: List<TocEntry>, onHit: (TextSearchHit, String) -> Unit) {
    // The chapter's own title where the contents name it; its number where they do not.
    val titles = remember(toc) { toc.groupBy { it.pageIndex }.mapValues { it.value.first().title } }
    Text(
        statusOf(state),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (!state.done) {
        LinearProgressIndicator(
            progress = { state.searched.toFloat() / state.chapters.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    LazyColumn(Modifier.heightIn(max = PANEL_MAX_HEIGHT)) {
        itemsIndexed(state.hits, key = { _, hit -> hit.chapter to hit.match.occurrence }) { _, hit ->
            ResultRow(hit, titles[hit.chapter], onClick = { onHit(hit, state.pattern) })
        }
    }
}

@Composable
private fun statusOf(state: TextSearchState): String = when {
    state.capped -> stringResource(R.string.reader_text_search_capped, MAX_HITS)
    state.done && state.hits.isEmpty() -> stringResource(R.string.reader_text_search_none)
    state.done -> stringResource(R.string.reader_text_search_done, state.hits.size)
    else -> stringResource(R.string.reader_text_search_progress, state.hits.size, state.searched, state.chapters)
}

@Composable
private fun ResultRow(hit: TextSearchHit, title: String?, onClick: () -> Unit) {
    val snippet = hit.match.snippet
    val text = buildAnnotatedString {
        append(snippet.substring(0, hit.match.matchStart))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
            append(snippet.substring(hit.match.matchStart, hit.match.matchEnd))
        }
        append(snippet.substring(hit.match.matchEnd))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = A11y.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            title ?: stringResource(R.string.reader_text_search_chapter, hit.chapter + 1),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PANEL_MAX_HEIGHT = 320.dp

/** Long enough to be a pause in typing, short enough to feel immediate. */
private const val TYPING_PAUSE_MS = 250L
