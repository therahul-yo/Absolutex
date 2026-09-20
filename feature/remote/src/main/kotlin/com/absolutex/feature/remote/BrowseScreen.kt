package com.absolutex.feature.remote

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.thumbnails.ThumbDecoder
import com.absolutex.core.thumbnails.ThumbRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folder browser: folders descend, books open in the reader. Covers resolve through
 * [com.absolutex.remote.core.TransportCoverFetcher] with bounded concurrency and decode
 * through the shared [ThumbDecoder] path at the pipeline's snapped width bucket; a book
 * whose cover fails (or is still loading) shows a fallback tile, so the list never waits
 * for covers. A book tap navigates the host to the reader route for its
 * `absolutex-remote://` Uri — Agent01 owns that routing, this screen only mints the Uri.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel = hiltViewModel(),
    onOpenBook: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val covers by viewModel.coversByPath.collectAsStateWithLifecycle()
    val upLabel = stringResource(R.string.remote_browse_up)

    BackHandler {
        if (!viewModel.goUp()) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!viewModel.goUp()) onBack()
                        },
                        modifier = Modifier.semantics { contentDescription = upLabel },
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        BrowseBody(
            state = state,
            covers = covers,
            onFolder = viewModel::openDir,
            onBook = onOpenBook,
            onBookTap = viewModel::onEntryTap,
            onRetry = viewModel::retry,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun titleFor(state: BrowseState): String {
    val fallback = stringResource(R.string.remote_browse_title)
    return when (state) {
        BrowseState.Loading -> fallback
        is BrowseState.Content -> state.path.substringAfterLast('/').ifEmpty { fallback }
        is BrowseState.Failed -> state.path.substringAfterLast('/').ifEmpty { fallback }
    }
}

@Composable
private fun BrowseBody(
    state: BrowseState,
    covers: Map<String, CoverState>,
    onFolder: (String) -> Unit,
    onBook: (String) -> Unit,
    onBookTap: (RemoteEntry, (String) -> Unit) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        BrowseState.Loading -> LoadingPane(modifier)
        is BrowseState.Failed -> FailedPane(onRetry = onRetry, modifier = modifier)
        is BrowseState.Content -> {
            if (state.entries.isEmpty()) {
                EmptyPane(modifier)
            } else {
                EntryGrid(
                    entries = state.entries,
                    covers = covers,
                    onFolder = onFolder,
                    onBookTap = { entry -> onBookTap(entry, onBook) },
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun LoadingPane(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.remote_browse_loading)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun EmptyPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.remote_browse_empty),
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun FailedPane(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.remote_retry)
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.remote_browse_failed))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Text(stringResource(R.string.remote_retry))
        }
    }
}

@Composable
private fun EntryGrid(
    entries: List<RemoteEntry>,
    covers: Map<String, CoverState>,
    onFolder: (String) -> Unit,
    onBookTap: (RemoteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_CELL_WIDTH_DP.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.path }) { entry ->
            if (entry.isDirectory) {
                FolderCell(entry = entry, onOpen = { onFolder(entry.path) })
            } else {
                BookCell(
                    entry = entry,
                    cover = covers[entry.path],
                    onOpen = { onBookTap(entry) },
                )
            }
        }
    }
}

@Composable
private fun FolderCell(entry: RemoteEntry, onOpen: () -> Unit) {
    val label = stringResource(R.string.remote_browse_folder, entry.name)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .clickable { onOpen() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Initial tile in the primary container: distinct from book covers by colour,
        // with no icon dependency beyond the core set.
        Box(
            Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.name.firstOrNull()?.uppercase() ?: "",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = entry.name,
            maxLines = CELL_LABEL_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookCell(entry: RemoteEntry, cover: CoverState?, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = entry.name }
            .clickable { onOpen() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (cover) {
            is CoverState.Ready -> CoverImage(bytes = cover.bytes, name = entry.name)
            else -> FallbackTile(name = entry.name)
        }
        Text(
            text = entry.name,
            maxLines = CELL_LABEL_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CoverImage(bytes: ByteArray, name: String) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    val cellPx = with(LocalDensity.current) { GRID_CELL_WIDTH_DP.dp.toPx() }.toInt()
    LaunchedEffect(bytes) {
        // The pipeline's decode step at its snapped bucket, off the main thread: corrupt
        // bytes decode to null and the tile falls back, like the pipeline's own callers.
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                ThumbDecoder.decode(bytes, ThumbRequest.snapWidth(cellPx))
            }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT),
        )
    } else {
        FallbackTile(name = name)
    }
}

@Composable
private fun FallbackTile(name: String) {
    val label = stringResource(R.string.remote_browse_no_cover, name)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val GRID_CELL_WIDTH_DP = 128
private const val CELL_LABEL_LINES = 2
private const val COVER_ASPECT = 2f / 3f
