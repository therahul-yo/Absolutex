package com.absolutex.feature.reader

import android.app.Activity
import android.os.Trace
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.decode.PageImage
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import com.absolutex.model.TapGrid
import com.absolutex.model.TapZone
import com.absolutex.model.column
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    uri: Uri,
    modifier: Modifier = Modifier,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.readerPrefs.collectAsStateWithLifecycle()

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
            else -> Pages(ui.pageCount, ui.currentPage, ui.bookId, prefs.readingFlow, prefs.fitMode, vm)
        }
    }
}

private const val CHROME_ALPHA = 0.9f

/** Grid columns that turn pages; the centre column is chrome. */
private const val FIRST_COLUMN = 0
private const val LAST_COLUMN = 2

@Composable
private fun Pages(
    pageCount: Int,
    startPage: Int,
    bookId: String,
    flow: ReadingFlow,
    fitMode: FitMode,
    vm: ReaderViewModel,
) {
    val pagerState = rememberPagerState(initialPage = startPage) { pageCount }
    // Per page, not one flag: page N's zoom or overflow must not lock the pager on page N+1.
    val locks = remember(pageCount) { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    // Edge swipes arrive in screen terms (finger left or up). A right-to-left book is laid out
    // mirrored, so there the finger moving left brings the previous page in, not the next.
    val goTo: (Int) -> Unit = { step ->
        scope.launch {
            pagerState.animateScrollToPage((pagerState.currentPage + step).coerceIn(0, pageCount - 1))
        }
    }
    val turn: (Boolean) -> Unit = { forward -> goTo(if (forward != (flow == ReadingFlow.RTL)) 1 else -1) }
    // §5.2 tap zones. The outer columns turn pages — the one way to turn that never competes with
    // the pager, which matters on a zoomed or overflowing page where the pager is switched off.
    // The centre column toggles the chrome.
    var chrome by remember { mutableStateOf(false) }
    val tap: (TapZone) -> Unit = { zone ->
        when (zone.column) {
            LAST_COLUMN -> goTo(1)
            FIRST_COLUMN -> goTo(-1)
            else -> chrome = !chrome
        }
    }
    ImmersiveWhile(hidden = !chrome)

    // Persist progress as the reader moves. snapshotFlow keeps this off the composition path.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageChanged(it) }
    }

    // §3's "tap book -> first page rendered" ends here, not at the first frame: the window is up
    // long before the page is decoded. ReportDrawnWhen turns that into timeToFullDisplayMs.
    var firstPageDrawn by remember(bookId) { mutableStateOf(false) }
    ReportDrawnWhen { firstPageDrawn }

    // A page is laid out the same way whichever pager hosts it; only the axis and direction change.
    val page: @Composable PagerScope.(Int) -> Unit = { index ->
        PageSlot(
            onBaseReady = { if (index == pagerState.currentPage) firstPageDrawn = true },
            // A neighbour waits until the page being looked at is up. beyondViewportPageCount
            // composes both neighbours immediately, and on the reference phone their decodes ran
            // alongside the current page's: three 6 MP JPEGs at once, and the one the reader is
            // waiting for came last (295 ms against 179 and 185).
            decodeNow = index == pagerState.currentPage || firstPageDrawn,
            index = index,
            bookId = bookId,
            vm = vm,
            fitMode = fitMode,
            rightToLeft = flow == ReadingFlow.RTL,
            pagerVertical = flow == ReadingFlow.VERTICAL,
            onPagerLockChanged = { locks[index] = it },
            onEdgeSwipe = turn,
            onTapZone = tap,
        )
    }
    // A zoomed or overflowing page owns its drags and turns itself at the edge (see PageCanvas).
    val scrollable = locks[pagerState.currentPage] != true
    Box(Modifier.fillMaxSize()) {
        ReaderPager(flow, pagerState, scrollable, page)
        ReaderChrome(
            visible = chrome,
            page = pagerState.currentPage,
            pageCount = pageCount,
            onSeek = { target -> scope.launch { pagerState.scrollToPage(target.coerceIn(0, pageCount - 1)) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The pager itself: same page slot either way, only the axis and direction change. */
@Composable
private fun ReaderPager(
    flow: ReadingFlow,
    pagerState: PagerState,
    scrollable: Boolean,
    page: @Composable PagerScope.(Int) -> Unit,
) {
    // TODO(phase3): beyondViewportPageCount should follow scroll velocity and the
    // prefetch depth setting (§3). Fixed at 1 for the Phase 2 slice.
    if (flow == ReadingFlow.VERTICAL) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scrollable,
            beyondViewportPageCount = 1,
            pageContent = page,
        )
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scrollable,
            beyondViewportPageCount = 1,
            // A manga reads right to left whatever language the phone is in. The pager already
            // mirrors under an RTL locale, so reverse only when the book and the UI disagree —
            // otherwise an Arabic-locale phone would read every Western comic backwards.
            reverseLayout = (flow == ReadingFlow.RTL) != (LocalLayoutDirection.current == LayoutDirection.Rtl),
            pageContent = page,
        )
    }
}

/**
 * Page indicator and seek bar, shown only with the chrome. Seeking is the only way to cross a
 * 45-page book in one move; tapping and swiping are both one page at a time.
 */
@Composable
private fun ReaderChrome(
    visible: Boolean,
    page: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible || pageCount <= 0) return
    // While dragging, the thumb and label follow the finger locally; the pager moves once, on
    // release. Seeking through the pager on every drag tick launched an animated scroll per tick,
    // each cancelling the last, and the page stuttered behind the thumb.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging?.roundToInt() ?: page) + 1
    val indicator = stringResource(R.string.reader_page_indicator_desc, shown, pageCount)
    val seekLabel = stringResource(R.string.reader_seek_desc)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.reader_page_indicator, shown, pageCount),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = indicator },
            )
            // Slider works in page numbers, not fractions: a 45-page book has 45 stops and the
            // value it reports is the page the reader lands on.
            Slider(
                value = dragging ?: page.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.roundToInt()) }
                    dragging = null
                },
                valueRange = 0f..(pageCount - 1).toFloat().coerceAtLeast(0f),
                // The slider's own value is 0-based; TalkBack should hear the page number shown.
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = seekLabel
                    stateDescription = indicator
                },
            )
        }
    }
}

/**
 * Immersive reading: the system bars are hidden while the chrome is, and a swipe from an edge
 * brings them back transiently without disturbing the page.
 */
@Composable
private fun ImmersiveWhile(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    LaunchedEffect(hidden, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun PageSlot(
    index: Int,
    bookId: String,
    vm: ReaderViewModel,
    fitMode: FitMode,
    rightToLeft: Boolean,
    pagerVertical: Boolean,
    onPagerLockChanged: (Boolean) -> Unit,
    onEdgeSwipe: (Boolean) -> Unit,
    onTapZone: (TapZone) -> Unit,
    onBaseReady: () -> Unit,
    decodeNow: Boolean,
) {
    var image by remember(index) { mutableStateOf<PageImage?>(null) }
    var attempts by remember(index) { mutableIntStateOf(0) }
    var loading by remember(index) { mutableStateOf(true) }
    LaunchedEffect(index, attempts, decodeNow) {
        if (!decodeNow) return@LaunchedEffect
        loading = true
        Trace.beginAsyncSection("absx.pageImage p=$index", index)
        image = vm.pageImage(index)
        Trace.endAsyncSection("absx.pageImage p=$index", index)
        loading = false
    }

    // A decode with no dimensions is unreadable, never rendered.
    val img = image?.takeIf { it.width > 0 && it.height > 0 }
    when {
        img != null -> PageCanvas(
            page = img,
            pageIndex = index,
            bookId = bookId,
            cache = vm.tileCache,
            fitMode = fitMode,
            rightToLeft = rightToLeft,
            pagerVertical = pagerVertical,
            onPagerLockChanged = onPagerLockChanged,
            onEdgeSwipe = onEdgeSwipe,
            onTapZone = onTapZone,
            onBaseReady = onBaseReady,
            baseLayer = { w, h -> vm.baseLayer(index, img, w, h) },
        )
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        // A settled load with no image must not spin forever: say so, and offer a retry
        // that clears both the cached entry and the failure mark.
        // A broken page must still be tapped past: tap zones are the one way to turn that never
        // depends on the page being drawable.
        else -> Box(
            Modifier.fillMaxSize()
                .pointerInput(rightToLeft) {
                    detectTapGestures { at ->
                        onTapZone(TapGrid.zoneAt(at.x, at.y, size.width, size.height, rightToLeft))
                    }
                }
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.reader_page_unreadable), color = Color.White)
                Button(onClick = {
                    vm.invalidatePage(index)
                    attempts++
                }) {
                    Text(stringResource(R.string.reader_retry))
                }
            }
        }
    }
}
