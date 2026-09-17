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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.absolutex.model.TocEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
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
import com.absolutex.model.FitContext
import com.absolutex.model.FitMode
import com.absolutex.core.data.settings.fitFor
import com.absolutex.core.data.settings.overriddenBy
import androidx.compose.ui.platform.LocalConfiguration
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
    /** Opens the settings destination; null where the host has none (previews, tests). */
    onSettings: (() -> Unit)? = null,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val global by vm.readerPrefs.collectAsStateWithLifecycle()
    // A book's own choices win over the global ones (§5.2), and only for this book.
    val options: ReaderOptionsViewModel = hiltViewModel()
    val book by remember(ui.bookId) { options.bookPrefs(ui.bookId) }.collectAsStateWithLifecycle(null)
    val prefs = global.overriddenBy(book)

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
            prefs.pageLayout == PageLayout.CONTINUOUS_VERTICAL ->
                Strip(ui.pageCount, vm.readingPage, ui.bookId, ui.title, prefs, vm, onSettings)
            else -> Pages(ui.pageCount, vm.readingPage, ui.bookId, ui.title, prefs, vm, onSettings)
        }
    }
}

internal const val CHROME_ALPHA = 0.9f

/** The most of the screen the chrome may take, so a page is always partly visible behind it. */
private const val CHROME_MAX_HEIGHT = 0.7f

/** One + or − press scales by a quarter; eight presses cross the whole zoom range. */
private const val KEY_ZOOM_STEP = 1.25f
private const val ZOOM_STEP_BUFFER = 4

private const val HALF = 0.5f

private const val PERCENT = 100f

@Composable
private fun Pages(
    pageCount: Int,
    startPage: Int,
    bookId: String,
    title: String,
    prefs: ReaderPrefs,
    vm: ReaderViewModel,
    onSettings: (() -> Unit)?,
) {
    val flow = prefs.readingFlow
    // The pager counts screens; everything else (progress, seeking, keys) speaks book pages.
    val spreads = remember(pageCount, prefs.pageLayout) { Spreads.of(pageCount, prefs.pageLayout) }
    val pagerState = rememberPagerState(initialPage = Spreads.indexOf(spreads, startPage)) { spreads.size }
    // A layout change regroups the pages: land on the page being read, not on whatever spread
    // happens to sit at the old index. Rebuilding the whole reader instead would also throw away
    // the open chrome, which is where the layout was just chosen.
    LaunchedEffect(spreads) { pagerState.scrollToPage(Spreads.indexOf(spreads, vm.readingPage)) }
    // Per page, not one flag: page N's zoom or overflow must not lock the pager on page N+1.
    val locks = remember(pageCount) { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    // Edge swipes arrive in screen terms; in a mirrored right-to-left book left brings the previous page.
    val goTo: (Int) -> Unit = { step -> scope.launch { pagerState.turn(step, spreads.lastIndex, prefs) } }
    val turn: (Boolean) -> Unit = { forward -> goTo(if (forward != (flow == ReadingFlow.RTL)) 1 else -1) }
    var chrome by remember { mutableStateOf(false) }
    val tap: (TapZone) -> Unit = { zone -> onTapZone(zone, goTo) { chrome = !chrome } }
    ReaderWindow(prefs, immersive = !chrome)

    // §5.3: keyboard and gamepad alone must be enough. Zoom goes only to the page being looked at.
    val zoomSteps = remember { MutableSharedFlow<Float>(extraBufferCapacity = ZOOM_STEP_BUFFER) }
    val jump: (Int) -> Unit = { to -> scope.launch { pagerState.scrollToPage(Spreads.indexOf(spreads, to)) } }
    val rtl = flow == ReadingFlow.RTL
    val vertical = flow == ReadingFlow.VERTICAL
    val keys = readerKeys(
        rtl, prefs.volumeKeysTurnPages, goTo, jump, pageCount - 1, zoomSteps::tryEmit,
    ) { chrome = !chrome }

    // Persist progress as the reader moves. snapshotFlow keeps this off the composition path.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageChanged(spreads[it].first) }
    }

    // §3's "tap book -> first page rendered" ends here, not at the first frame: the window is up
    // long before the page is decoded. ReportDrawnWhen turns that into timeToFullDisplayMs.
    var firstPageDrawn by remember(bookId) { mutableStateOf(false) }
    ReportDrawnWhen { firstPageDrawn }
    val toc by vm.toc.collectAsStateWithLifecycle()
    var landscapePage by remember(bookId) { mutableStateOf(false) }
    val fitContext = rememberFitContext(landscapePage)

    // A page is laid out the same way whichever pager hosts it; only the axis and direction change.
    val page: @Composable PagerScope.(Int) -> Unit = { screen ->
        val spread = spreads[screen]
        val current = screen == pagerState.currentPage
        SpreadRow(spread, rtl, Modifier.transition(pagerState, screen, prefs.transition, vertical)) { index, side ->
            PageSlot(
                index = index, bookId = bookId, vm = vm, fitMode = null, prefs = prefs, rightToLeft = rtl,
                pagerVertical = vertical,
                onLoaded = { if (current) landscapePage = it.width > it.height },
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
            visible = chrome, page = spreads[pagerState.currentPage].first, pageCount = pageCount,
            title = title, onSettings = onSettings, onSeek = jump,
            bookId = bookId, strip = vm::thumbnail.takeIf { prefs.thumbnailStrip }, toc = toc,
            onExport = { vm.exportPage(spreads[pagerState.currentPage].first) },
            fitFor = fitContext, prefs = prefs,
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
private fun Strip(
    pageCount: Int,
    startPage: Int,
    bookId: String,
    title: String,
    prefs: ReaderPrefs,
    vm: ReaderViewModel,
    onSettings: (() -> Unit)?,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    ReaderWindow(prefs, immersive = !chrome)
    val step: (Int) -> Unit = { direction ->
        scope.launch {
            val info = listState.layoutInfo
            val extent = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            listState.animateScrollBy(
                direction * extent * prefs.scrollStepPercent / PERCENT,
                animationSpec = tween(prefs.pageTurnMs),
            )
        }
    }
    val jump: (Int) -> Unit = { to -> scope.launch { listState.scrollToItem(to.coerceIn(0, pageCount - 1)) } }
    val tap: (TapZone) -> Unit = { zone -> onTapZone(zone, step) { chrome = !chrome } }
    val rtl = prefs.readingFlow == ReadingFlow.RTL
    // ponytail: no keyboard zoom in a strip; pinch zooms a page in place. Add when a strip has a focus page.
    val keys = readerKeys(rtl, prefs.volumeKeysTurnPages, step, jump, pageCount - 1, { false }) { chrome = !chrome }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { vm.onPageChanged(it) }
    }
    var firstPageDrawn by remember(bookId) { mutableStateOf(false) }
    ReportDrawnWhen { firstPageDrawn }
    val toc by vm.toc.collectAsStateWithLifecycle()
    val aspects = remember(bookId) { mutableStateMapOf<Int, Float>() }

    Box(Modifier.fillMaxSize().then(keys)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(pageCount) { index ->
                val aspect = aspects[index]
                val size = aspect?.let { Modifier.fillMaxWidth().aspectRatio(it) } ?: Modifier.fillParentMaxSize()
                Box(size.clipToBounds()) {
                    PageSlot(
                        index = index, bookId = bookId, vm = vm, fitMode = FitMode.FIT_WIDTH, prefs = prefs,
                        rightToLeft = rtl,
                        pagerVertical = true, onPagerLockChanged = {}, onEdgeSwipe = {}, onTapZone = tap,
                        spreadSide = SpreadSide.NONE, onBaseReady = { if (index == startPage) firstPageDrawn = true },
                        decodeNow = true, zoomSteps = null,
                        onLoaded = { aspects[index] = it.width.toFloat() / it.height },
                    )
                }
            }
        }
        ReaderChrome(
            visible = chrome, page = listState.firstVisibleItemIndex, pageCount = pageCount,
            title = title, onSettings = onSettings, onSeek = jump,
            bookId = bookId, strip = vm::thumbnail.takeIf { prefs.thumbnailStrip }, toc = toc,
            onExport = { vm.exportPage(listState.firstVisibleItemIndex) },
            fitFor = null, prefs = prefs,
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
    modifier: Modifier = Modifier,
    slot: @Composable (index: Int, side: SpreadSide) -> Unit,
) {
    if (spread.first == spread.last) {
        Box(modifier.fillMaxSize()) { slot(spread.first, SpreadSide.NONE) }
        return
    }
    val (left, right) = if (rightToLeft) spread.last to spread.first else spread.first to spread.last
    Box(modifier.fillMaxSize()) {
        // Clipped: a canvas draws outside its bounds, and a zoomed page would cover its neighbour.
        Box(Modifier.fillMaxWidth(HALF).fillMaxHeight().align(AbsoluteAlignment.CenterLeft).clipToBounds()) {
            slot(left, SpreadSide.LEFT)
        }
        Box(Modifier.fillMaxWidth(HALF).fillMaxHeight().align(AbsoluteAlignment.CenterRight).clipToBounds()) {
            slot(right, SpreadSide.RIGHT)
        }
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
    title: String,
    onSettings: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    bookId: String,
    strip: (suspend (index: Int, width: Int) -> Bitmap?)?,
    toc: List<TocEntry>,
    onExport: suspend () -> Uri?,
    /** Null in a layout whose fit is fixed, such as the continuous strip. */
    fitFor: FitContext?,
    prefs: ReaderPrefs,
    modifier: Modifier = Modifier,
) {
    if (!visible || pageCount <= 0) return
    Box(modifier.fillMaxSize()) {
        ReaderTopBar(title, onSettings, Modifier.align(Alignment.TopCenter))
    // While dragging, the thumb and label follow the finger locally; the pager moves once, on
    // release. Seeking through the pager on every drag tick launched an animated scroll per tick,
    // each cancelling the last, and the page stuttered behind the thumb.
    var dragging by remember { mutableStateOf<Float?>(null) }
    var contents by remember { mutableStateOf(false) }
    // Landscape has ~1200 px of height and the chrome had grown past it, so the options moved
    // behind a toggle: what is always shown is what a reader looks at every page.
    var options by remember { mutableStateOf(false) }
    val shown = (dragging?.roundToInt() ?: page) + 1
    val indicator = stringResource(R.string.reader_page_indicator_desc, shown, pageCount)
    val seekLabel = stringResource(R.string.reader_seek_desc)
    // Capped and scrollable: with the options open, landscape has ~1200 px of height and the
    // chrome would otherwise grow over its own top bar and the page entirely.
    val maxChrome = (LocalConfiguration.current.screenHeightDp * CHROME_MAX_HEIGHT).dp
    val chromeScroll = rememberScrollState()
    // Opening the options scrolls to them: they sit below the seek bar, which in landscape is past
    // the cap, and a control that appears to do nothing is worse than no control.
    LaunchedEffect(options) {
        if (options) {
            withFrameNanos { }
            chromeScroll.animateScrollTo(chromeScroll.maxValue)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .heightIn(max = maxChrome).navigationBarsPadding(),
    ) {
        Column(
            Modifier.verticalScroll(chromeScroll).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (contents) TocPanel(toc, onJump = { onSeek(it); contents = false })
            ChromeActions(
                indicator = stringResource(R.string.reader_page_indicator, shown, pageCount),
                indicatorDescription = indicator,
                hasContents = toc.isNotEmpty(),
                onContents = { contents = !contents },
                onOptions = { options = !options },
                page = page,
                onExport = onExport,
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
            // Last, not first: the seek bar and the strip are what a reader reaches for on every
            // page, so they keep the top of the capped box and the options open below them.
            if (options) {
                fitFor?.let { FitRow(prefs, it) }
                BookOptionsRow(bookId, prefs)
            }
        }
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
    /** Forces one fit, for a layout that has no choice: a continuous strip is fit to width. */
    fitMode: FitMode?,
    prefs: ReaderPrefs,
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
    // The fit follows the shape of what is actually on screen, which is only known once the page's
    // header is read: a spread and a single page are different situations with different answers.
    val screen = LocalConfiguration.current
    val fit = fitMode ?: img?.let {
        prefs.fitFor(FitContext.of(screen.screenWidthDp, screen.screenHeightDp, it.width, it.height))
    } ?: prefs.fitMode
    when {
        img != null -> PageCanvas(
            page = img,
            pageIndex = index,
            bookId = bookId,
            cache = vm.tileCache,
            fitMode = fit,
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
