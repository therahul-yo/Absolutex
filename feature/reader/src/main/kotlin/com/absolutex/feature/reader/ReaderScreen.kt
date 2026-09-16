package com.absolutex.feature.reader

import com.absolutex.core.data.settings.RotationLock
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
import android.content.pm.ActivityInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import com.absolutex.core.data.settings.ReaderPrefs
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.focusable
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.app.Activity
import android.graphics.Bitmap
import android.os.Trace
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import com.absolutex.model.PageLayout
import androidx.compose.runtime.key
import androidx.compose.ui.AbsoluteAlignment
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
import com.absolutex.model.Spreads
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
            // A layout change regroups the pages, so the pager restarts on the page being read.
            else -> key(prefs.pageLayout) {
                if (prefs.pageLayout == PageLayout.CONTINUOUS_VERTICAL) {
                    Strip(ui.pageCount, vm.readingPage, ui.bookId, prefs, vm)
                } else {
                    Pages(ui.pageCount, vm.readingPage, ui.bookId, prefs, vm)
                }
            }
        }
    }
}

private const val CHROME_ALPHA = 0.9f

/** One + or − press scales by a quarter; eight presses cross the whole zoom range. */
private const val KEY_ZOOM_STEP = 1.25f
private const val ZOOM_STEP_BUFFER = 4

/** Grid columns that turn pages; the centre column is chrome. */
private const val FIRST_COLUMN = 0
private const val LAST_COLUMN = 2

private const val HALF = 0.5f

/** A key press or edge tap in a strip moves this much of a screen, keeping a line of context. */
private const val STRIP_STEP = 0.9f

@Composable
private fun Pages(
    pageCount: Int,
    startPage: Int,
    bookId: String,
    prefs: ReaderPrefs,
    vm: ReaderViewModel,
) {
    val flow = prefs.readingFlow
    // The pager counts screens; everything else (progress, seeking, keys) speaks book pages.
    val spreads = remember(pageCount, prefs.pageLayout) { Spreads.of(pageCount, prefs.pageLayout) }
    val pagerState = rememberPagerState(initialPage = Spreads.indexOf(spreads, startPage)) { spreads.size }
    // Per page, not one flag: page N's zoom or overflow must not lock the pager on page N+1.
    val locks = remember(pageCount) { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    // Edge swipes arrive in screen terms; in a mirrored right-to-left book left brings the previous page.
    val goTo: (Int) -> Unit = { step ->
        scope.launch {
            pagerState.animateScrollToPage((pagerState.currentPage + step).coerceIn(0, spreads.lastIndex))
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
    ReaderWindow(prefs, immersive = !chrome)

    // §5.3: keyboard and gamepad alone must be enough. Zoom goes only to the page being looked at.
    val zoomSteps = remember { MutableSharedFlow<Float>(extraBufferCapacity = ZOOM_STEP_BUFFER) }
    val jump: (Int) -> Unit = { to -> scope.launch { pagerState.scrollToPage(Spreads.indexOf(spreads, to)) } }
    val rtl = flow == ReadingFlow.RTL
    val keys = readerKeys(rtl, prefs.volumeKeysTurnPages, goTo, jump, pageCount - 1, zoomSteps::tryEmit) {
        chrome = !chrome
    }

    // Persist progress as the reader moves. snapshotFlow keeps this off the composition path.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageChanged(spreads[it].first) }
    }

    // §3's "tap book -> first page rendered" ends here, not at the first frame: the window is up
    // long before the page is decoded. ReportDrawnWhen turns that into timeToFullDisplayMs.
    var firstPageDrawn by remember(bookId) { mutableStateOf(false) }
    ReportDrawnWhen { firstPageDrawn }

    // A page is laid out the same way whichever pager hosts it; only the axis and direction change.
    val page: @Composable PagerScope.(Int) -> Unit = { screen ->
        val spread = spreads[screen]
        val current = screen == pagerState.currentPage
        SpreadRow(spread, rtl) { index, side ->
            PageSlot(
                index = index, bookId = bookId, vm = vm, fitMode = prefs.fitMode, rightToLeft = rtl,
                pagerVertical = flow == ReadingFlow.VERTICAL,
                onBaseReady = { if (current && index == spread.first) firstPageDrawn = true },
                // Neighbours wait for the page on screen: decoded together, it finished last (see PageSlot).
                decodeNow = current || firstPageDrawn,
                onPagerLockChanged = { locks[index] = it },
                onEdgeSwipe = turn, onTapZone = tap, spreadSide = side,
                zoomSteps = if (current) zoomSteps else null,
            )
        }
    }
    // A zoomed or overflowing page owns its drags and turns itself at the edge (see PageCanvas).
    val scrollable = spreads[pagerState.currentPage].none { locks[it] == true }
    Box(Modifier.fillMaxSize().then(keys)) {
        ReaderPager(flow, pagerState, scrollable, page)
        ReaderChrome(
            visible = chrome, page = spreads[pagerState.currentPage].first, pageCount = pageCount, onSeek = jump,
            bookId = bookId, strip = vm::thumbnail.takeIf { prefs.thumbnailStrip },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Continuous vertical reading (§5.2): every page fit to width in one scrolling column, for webtoons
 * and strips whose "pages" are cut wherever the scanner felt like it.
 *
 * Each page's height is unknown until its header is read, so it holds a screen until then; the
 * ratio is remembered for the book, so scrolling back never resizes what is above the reader.
 * Keys and edge taps move by most of a screen, not by an item: one item can be many screens tall.
 */
@Composable
private fun Strip(pageCount: Int, startPage: Int, bookId: String, prefs: ReaderPrefs, vm: ReaderViewModel) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    ReaderWindow(prefs, immersive = !chrome)
    val step: (Int) -> Unit = { direction ->
        scope.launch {
            val info = listState.layoutInfo
            listState.animateScrollBy(direction * (info.viewportEndOffset - info.viewportStartOffset) * STRIP_STEP)
        }
    }
    val jump: (Int) -> Unit = { to -> scope.launch { listState.scrollToItem(to.coerceIn(0, pageCount - 1)) } }
    val tap: (TapZone) -> Unit = { zone ->
        when (zone.column) {
            LAST_COLUMN -> step(1)
            FIRST_COLUMN -> step(-1)
            else -> chrome = !chrome
        }
    }
    val rtl = prefs.readingFlow == ReadingFlow.RTL
    // ponytail: no keyboard zoom in a strip; pinch zooms a page in place. Add when a strip has a focus page.
    val keys = readerKeys(rtl, prefs.volumeKeysTurnPages, step, jump, pageCount - 1, { false }) { chrome = !chrome }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { vm.onPageChanged(it) }
    }
    var firstPageDrawn by remember(bookId) { mutableStateOf(false) }
    ReportDrawnWhen { firstPageDrawn }
    val aspects = remember(bookId) { mutableStateMapOf<Int, Float>() }

    Box(Modifier.fillMaxSize().then(keys)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(pageCount) { index ->
                val aspect = aspects[index]
                val size = aspect?.let { Modifier.fillMaxWidth().aspectRatio(it) } ?: Modifier.fillParentMaxSize()
                Box(size.clipToBounds()) {
                    PageSlot(
                        index = index, bookId = bookId, vm = vm, fitMode = FitMode.FIT_WIDTH, rightToLeft = rtl,
                        pagerVertical = true, onPagerLockChanged = {}, onEdgeSwipe = {}, onTapZone = tap,
                        spreadSide = SpreadSide.NONE, onBaseReady = { if (index == startPage) firstPageDrawn = true },
                        decodeNow = true, zoomSteps = null,
                        onLoaded = { aspects[index] = it.width.toFloat() / it.height },
                    )
                }
            }
        }
        ReaderChrome(
            visible = chrome, page = listState.firstVisibleItemIndex, pageCount = pageCount, onSeek = jump,
            bookId = bookId, strip = vm::thumbnail.takeIf { prefs.thumbnailStrip },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Focus plus key handling for the reader surface. Focus is requested on entry: without it no key
 * reaches the reader and a keyboard or gamepad user cannot even start. Key-down only, so a held key
 * repeats through the system's own key repeat rather than firing twice per press.
 */
@Composable
@Suppress("LongParameterList")
private fun readerKeys(
    rightToLeft: Boolean,
    volumeKeys: Boolean,
    onStep: (Int) -> Unit,
    onJump: (Int) -> Unit,
    lastPage: Int,
    onZoom: (Float) -> Boolean,
    onToggleChrome: () -> Unit,
): Modifier {
    val focus = remember { FocusRequester() }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val dispatch by rememberUpdatedState { action: ReaderKeyAction ->
        when (action) {
            ReaderKeyAction.NEXT_PAGE -> onStep(1)
            ReaderKeyAction.PREVIOUS_PAGE -> onStep(-1)
            ReaderKeyAction.FIRST_PAGE -> onJump(0)
            ReaderKeyAction.LAST_PAGE -> onJump(lastPage)
            ReaderKeyAction.ZOOM_IN -> onZoom(KEY_ZOOM_STEP)
            ReaderKeyAction.ZOOM_OUT -> onZoom(1f / KEY_ZOOM_STEP)
            ReaderKeyAction.TOGGLE_CHROME -> onToggleChrome()
            ReaderKeyAction.BACK -> back?.onBackPressed()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    return Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val action = ReaderKeys.actionFor(
                event.nativeKeyEvent.keyCode, event.isShiftPressed, rightToLeft, volumeKeys,
            ) ?: return@onPreviewKeyEvent false
            dispatch(action)
            true
        }
}

/**
 * One screen of the pager: a page alone, or two facing pages, each in its half. Placed by absolute
 * left and right, not start and end: the book's flow decides which page is on the left, never the
 * phone's language.
 */
@Composable
private fun SpreadRow(
    spread: IntRange,
    rightToLeft: Boolean,
    slot: @Composable (index: Int, side: SpreadSide) -> Unit,
) {
    if (spread.first == spread.last) {
        slot(spread.first, SpreadSide.NONE)
        return
    }
    val (left, right) = if (rightToLeft) spread.last to spread.first else spread.first to spread.last
    Box(Modifier.fillMaxSize()) {
        // Clipped: a canvas draws outside its bounds, and a zoomed page would cover its neighbour.
        Box(Modifier.fillMaxWidth(HALF).fillMaxHeight().align(AbsoluteAlignment.CenterLeft).clipToBounds()) {
            slot(left, SpreadSide.LEFT)
        }
        Box(Modifier.fillMaxWidth(HALF).fillMaxHeight().align(AbsoluteAlignment.CenterRight).clipToBounds()) {
            slot(right, SpreadSide.RIGHT)
        }
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
    bookId: String,
    strip: (suspend (index: Int, width: Int) -> Bitmap?)?,
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
            strip?.let { ThumbnailStrip(pageCount, page, bookId, onSeek, it) }
            BookmarkBar(bookId, page, pageCount, onJump = onSeek)
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
 * The window behaviour §5.2 makes settings: keep the screen on, lock rotation, draw under the
 * cutout. Applied while the reader is on screen and put back when it leaves, so the rest of the app
 * never inherits a locked orientation or a screen that will not sleep.
 */
@Composable
private fun ReaderWindow(prefs: ReaderPrefs, immersive: Boolean) {
    ImmersiveWhile(hidden = immersive)
    val view = LocalView.current
    val activity = view.context as? Activity
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(prefs.rotationLock, activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = when (prefs.rotationLock) {
            RotationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            RotationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            RotationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    DisposableEffect(prefs.useCutout, activity) {
        val window = activity?.window
        val previous = window?.attributes?.layoutInDisplayCutoutMode
        window?.attributes = window?.attributes?.apply {
            layoutInDisplayCutoutMode = if (prefs.useCutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previous }
            }
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
    spreadSide: SpreadSide,
    onBaseReady: () -> Unit,
    /**
     * False until this page may start decoding. beyondViewportPageCount composes both neighbours
     * immediately, and on the reference phone their decodes ran alongside the current page's: three
     * 6 MP JPEGs at once, and the one the reader was waiting for came last (295 ms against 179, 185).
     */
    decodeNow: Boolean,
    zoomSteps: Flow<Float>?,
    /** The page's header is read: its dimensions are known. Only valid (non-empty) pages. */
    onLoaded: (PageImage) -> Unit = {},
) {
    var image by remember(index) { mutableStateOf<PageImage?>(null) }
    var attempts by remember(index) { mutableIntStateOf(0) }
    var loading by remember(index) { mutableStateOf(true) }
    LaunchedEffect(index, attempts, decodeNow) {
        if (!decodeNow) return@LaunchedEffect
        loading = true
        Trace.beginAsyncSection("absx.pageImage p=$index", index)
        image = vm.pageImage(index)
        image?.takeIf { it.width > 0 && it.height > 0 }?.let(onLoaded)
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
            spreadSide = spreadSide,
            onBaseReady = onBaseReady,
            baseLayer = { w, h -> vm.baseLayer(index, img, w, h) },
            zoomSteps = zoomSteps,
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
                .pointerInput(rightToLeft, spreadSide) {
                    detectTapGestures { at ->
                        onTapZone(spreadSide.zoneAt(at.x, at.y, size.width, size.height, rightToLeft))
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
