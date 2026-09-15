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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            ui.error != null -> Text(ui.error!!, color = Color.White, modifier = Modifier.padding(24.dp))
            ui.pageCount == 0 -> Text("No readable pages", color = Color.White)
            else -> Pages(ui.pageCount, ui.currentPage, vm)
        }
    }
}

@Composable
private fun Pages(pageCount: Int, startPage: Int, vm: ReaderViewModel) {
    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }
    var zoom by remember { mutableStateOf(1f) }

    // Persist progress as the reader moves. snapshotFlow keeps this off the composition path.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageChanged(it) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // A zoomed page owns its drags; re-enabled the moment it returns to fit scale.
        userScrollEnabled = zoom <= 1f,
        // TODO(phase3): beyondViewportPageCount should follow scroll velocity and the
        // prefetch depth setting (§3). Fixed at 1 for the Phase 2 slice.
        beyondViewportPageCount = 1,
    ) { index ->
        var image by remember(index) { mutableStateOf<PageImage?>(null) }
        var attempts by remember(index) { mutableIntStateOf(0) }
        var loading by remember(index) { mutableStateOf(true) }
        LaunchedEffect(index, attempts) {
            loading = true
            image = vm.pageImage(index)
            loading = false
        }

        // Corrupt decodes (width<=0) are treated as unreadable, never rendered.
        val img = image?.takeIf { it.width > 0 && it.height > 0 }
        when {
            img != null -> {
                PageCanvas(
                    page = img,
                    pageIndex = index,
                    cache = vm.tileCache,
                    onZoomChanged = { zoom = it },
                )
            }
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // Null must not spin forever: after the load settles with no image,
                // surface the failure with a retry that clears the cache entry.
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Page unreadable", color = Color.White)
                        Button(onClick = {
                            vm.invalidatePage(index)
                            attempts++
                        }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
