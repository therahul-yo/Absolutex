package com.absolutex.feature.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.decode.PageImage

@Composable
fun ReaderScreen(
    uri: Uri,
    modifier: Modifier = Modifier,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(uri) { vm.open(uri) }

    Box(
        modifier
            .fillMaxSize()
            // The reading surface is not a Material surface, it is the page (§7).
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            ui.loading -> CircularProgressIndicator()
            // Generic string from the ViewModel — never a raw Uri or entry name.
            ui.error != null -> Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(ui.error!!, color = Color.White)
                Button(onClick = { vm.open(uri) }) {
                    Text(stringResource(R.string.reader_retry))
                }
            }
            ui.pageCount == 0 -> Text(
                stringResource(R.string.reader_no_pages),
                color = Color.White,
            )
            else -> Pages(ui.pageCount, ui.currentPage, vm)
        }
    }
}

@Composable
private fun Pages(pageCount: Int, startPage: Int, vm: ReaderViewModel) {
    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }
    // Per-page zoom: a single var would let page N's zoom leak into page N+1's
    // userScrollEnabled. Hysteresis (1.05f) keeps the pager from flickering at the boundary.
    val zooms = remember(pageCount) { mutableStateMapOf<Int, Float>() }
    val failedPages by vm.failedPages.collectAsStateWithLifecycle()

    // Persist progress as the reader moves. snapshotFlow keeps this off the composition path.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageChanged(it) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // A zoomed page owns its drags; re-enabled the moment it returns to fit scale.
        userScrollEnabled = (zooms[pagerState.currentPage] ?: 1f) <= 1.05f,
        // TODO(phase3): beyondViewportPageCount should follow scroll velocity and the
        // prefetch depth setting (§3). Fixed at 1 for the Phase 2 slice.
        beyondViewportPageCount = 1,
    ) { index ->
        var image by remember(index) { mutableStateOf<PageImage?>(null) }
        LaunchedEffect(index) { image = vm.pageImage(index) }

        val img = image
        when {
            img != null -> PageCanvas(
                page = img,
                pageIndex = index,
                cache = vm.tileCache,
                onZoomChanged = { scale ->
                    // Only 1f-boundary crossings update the map (old vs new across 1.02f),
                    // so per-frame pinch callbacks never recompose the pager.
                    val old = zooms[index] ?: 1f
                    if ((old <= 1.02f) != (scale <= 1.02f)) {
                        zooms[index] = scale
                    }
                },
            )
            index in failedPages -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reader_page_unreadable), color = Color.White)
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
