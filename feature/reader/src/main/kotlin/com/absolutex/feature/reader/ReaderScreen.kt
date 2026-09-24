package com.absolutex.feature.reader

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import com.absolutex.core.ui.Motion
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import com.absolutex.core.data.settings.RotationLock
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
import android.content.pm.ActivityInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.gpu.CropRect
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import com.absolutex.model.PageLayout
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /**
     * Turning forward past the last screen (§5.2 auto-advance); null where the host has nowhere
     * to send it (previews, tests). Fires once per attempt, never on every frame — see [Pages]
     * and [Strip].
     */
    onFinished: (() -> Unit)? = null,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val global by vm.readerPrefs.collectAsStateWithLifecycle()
    val rendering by vm.renderingPrefs.collectAsStateWithLifecycle()
    // A book's own choices win over the global ones (§5.2), and only for this book.
    val options: ReaderOptionsViewModel = hiltViewModel()
    val book by remember(ui.bookId) { options.bookPrefs(ui.bookId) }.collectAsStateWithLifecycle(null)
    val prefs = global.overriddenBy(book)
    // Hoisted above the Pages/Strip split so choosing the continuous-vertical layout from the
    // open chrome does not close it: Pages and Strip are different call sites, and a `remember`
    // owned by either one is torn down the moment the `when` below takes the other branch.
    // rememberSaveable also survives a config change this activity does not declare (font scale,
    // locale, keyboard) — MainActivity would otherwise have to declare every such change instead.
    val chromeState = rememberSaveable { mutableStateOf(false) }
    // Auto background (§4, §5.2, milestone 5): hoisted like chromeState above — Pages and Strip
    // are different call sites, and state owned by either one would be torn down the moment the
    // other is composed instead. One colour per settled page (PageCanvas reports one per page it
    // loads); backgroundPage names which of those is live right now. TODO(lead) from #26 done here.
    val pageBackgrounds = remember { mutableStateMapOf<Int, Color>() }
    val backgroundPage = remember { mutableStateOf(0) }
    val targetBackground = readerBackgroundFor(rendering.autoBackground, pageBackgrounds, backgroundPage.value)
    val animatedBackground by animateColorAsState(targetBackground, label = "readerBackground")

    LaunchedEffect(uri) { vm.open(uri) }

    Box(
        modifier
            .fillMaxSize()
            // The reading surface is not a Material surface, it is the page (§7). Auto background
            // crossfades this to the settled page's own edge colour; off (or before a first
            // report) it is exactly today's flat black.
            .background(animatedBackground),
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
                Strip(
                    ui.pageCount, vm.readingPage, ui.bookId, ui.title, prefs, vm, onSettings, onFinished,
                    chromeState, pageBackgrounds, backgroundPage,
                )
            else ->
                Pages(
                    ui.pageCount, vm.readingPage, ui.bookId, ui.title, prefs, vm, onSettings, onFinished,
                    chromeState, pageBackgrounds, backgroundPage,
                )
        }
        // Visible on open, also in the empty recovery case. Chrome has its own top bar; do not
        // cover its controls. The report never participates in page counts or seeking.
        if (!chromeState.value || ui.pageCount == 0) {
            ui.recoveryNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = CHROME_ALPHA),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                ) {
                    Text(notice, modifier = Modifier.padding(16.dp))
                }
            }
        }
        // The prompt overlays every state, including the generic failure it rides on.
        PasswordPrompt(ui.passwordRequired, ui.passwordIncorrect, { vm.open(uri, it) }, vm::cancelPasswordPrompt)
    }
}

/**
 * The letterbox's target colour right now (§4, §5.2, milestone 5): the settled page's own
 * sampled colour when auto background is on and that page has reported one; opaque black
 * otherwise — off, or before any page has settled — which is exactly today's flat background.
 * Pure and total, so the one-colour-per-settled-page rule is unit-tested on the JVM, with no
 * page geometry, animation or instrumentation harness involved.
 */
internal fun readerBackgroundFor(
    autoBackground: Boolean,
    pageBackgrounds: Map<Int, Color>,
    settledPage: Int,
): Color = if (autoBackground) pageBackgrounds[settledPage] ?: Color.Black else Color.Black

internal const val CHROME_ALPHA = 0.97f

/** The most of the screen the chrome may take, so a page is always partly visible behind it. */
internal const val CHROME_MAX_HEIGHT = 0.7f

/** One + or − press scales by a quarter; eight presses cross the whole zoom range. */
private const val KEY_ZOOM_STEP = 1.25f
private const val ZOOM_STEP_BUFFER = 4

private const val HALF = 0.5f

/** Shared with [pagerStep] and [stripStep] in ReaderAdvance.kt. */
internal const val PERCENT = 100f

@Composable
private fun Pages(
    pageCount: Int,
    startPage: Int,
    bookId: String,
    title: String,
    prefs: ReaderPrefs,
    vm: ReaderViewModel,
    onSettings: (() -> Unit)?,
    onFinished: (() -> Unit)?,
    chromeState: MutableState<Boolean>,
    /** One sampled background colour per page loaded so far (§4, §5.2, milestone 5). */
    pageBackgrounds: MutableMap<Int, Color>,
    /** Which page's colour the letterbox is crossfading toward. */
    backgroundPage: MutableState<Int>,
) {
    val flow = prefs.readingFlow
    // The pager counts screens; everything else (progress, seeking, keys) speaks book pages.
    val layout = prefs.pageLayout.forScreen(LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE)
    val spreads = remember(pageCount, layout) { Spreads.of(pageCount, layout) }
    val pagerState = rememberPagerState(initialPage = Spreads.indexOf(spreads, startPage)) { spreads.size }
    // A layout change regroups the pages: land on the page being read, not on whatever spread
    // happens to sit at the old index. Rebuilding the whole reader instead would also throw away
    // the open chrome, which is where the layout was just chosen.
    LaunchedEffect(spreads) { pagerState.scrollToPage(Spreads.indexOf(spreads, vm.readingPage)) }
    // Per page, not one flag: page N's zoom or overflow must not lock the pager on page N+1.
    val locks = remember(pageCount) { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    // Edge swipes arrive in screen terms; in a mirrored right-to-left book left brings the previous
    // page. See pagerStep (ReaderAdvance.kt) for the last-screen / onFinished behaviour.
    val goTo: (Int) -> Unit = pagerStep(pagerState, spreads.lastIndex, prefs, scope, onFinished)
    val turn: (Boolean) -> Unit = { forward -> goTo(if (forward != (flow == ReadingFlow.RTL)) 1 else -1) }
    var chrome by chromeState
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
    // Keyed on pagerState, not spreads: it must keep running, unbroken, across a page-layout
    // change (spreads is recreated, but the pager itself is not). rememberUpdatedState is what
    // makes that safe — without it this coroutine would go on closing over the FIRST spreads it
    // ever saw, indexing settledPage into an array sized for a different layout: out of bounds
    // (a crash) if the new grouping is finer, or a wrong page silently written to progress if
    // it is coarser.
    val currentSpreads by rememberUpdatedState(spreads)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            // A settled index that outran a just-shrunk spreads list is stale, not a page: skip
            // it rather than write the wrong page to progress. The re-targeting effect follows.
            currentSpreads.getOrNull(settled)?.let { vm.settlePage(it.first, backgroundPage) }
        }
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
                onBackgroundColour = { pageBackgrounds[index] = it },
            )
        }
    }
    // A zoomed or overflowing page owns its drags and turns itself at the edge (see PageCanvas).
    val scrollable = Spreads.at(spreads, pagerState.currentPage).none { locks[it] == true }
    Box(Modifier.fillMaxSize().then(keys)) {
        ReaderPager(flow, pagerState, scrollable, page)
        ReaderChrome(
            visible = chrome, page = Spreads.at(spreads, pagerState.currentPage).first, pageCount = pageCount,
            title = title, onSettings = onSettings, onSeek = jump, onDismiss = { chrome = false },
            bookId = bookId, strip = vm::thumbnail.takeIf { prefs.thumbnailStrip }, toc = toc,
            onExport = { vm.exportPage(Spreads.at(spreads, pagerState.currentPage).first) },
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
    onFinished: (() -> Unit)?,
    chromeState: MutableState<Boolean>,
    /** One sampled background colour per page loaded so far (§4, §5.2, milestone 5). */
    pageBackgrounds: MutableMap<Int, Color>,
    /** Which page's colour the letterbox is crossfading toward. */
    backgroundPage: MutableState<Int>,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val scope = rememberCoroutineScope()
    var chrome by chromeState
    ReaderWindow(prefs, immersive = !chrome)
    // See stripStep (ReaderAdvance.kt) for the can't-scroll-further / onFinished behaviour.
    val step: (Int) -> Unit = stripStep(listState, prefs, scope, onFinished)
    val jump: (Int) -> Unit = { to -> scope.launch { listState.scrollToItem(to.coerceIn(0, pageCount - 1)) } }
    val tap: (TapZone) -> Unit = { zone -> onTapZone(zone, step) { chrome = !chrome } }
    val rtl = prefs.readingFlow == ReadingFlow.RTL
    // ponytail: no keyboard zoom in a strip; pinch zooms a page in place. Add when a strip has a focus page.
    val keys = readerKeys(rtl, prefs.volumeKeysTurnPages, step, jump, pageCount - 1, { false }) { chrome = !chrome }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { vm.settlePage(it, backgroundPage) }
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
                        onCropDecided = { crop ->
                            val a = crop?.let { it.width.toFloat() / it.height }
                            if (a != null) aspects[index] = a
                        },
                        onBackgroundColour = { pageBackgrounds[index] = it },
                    )
                }
            }
        }
        ReaderChrome(
            visible = chrome, page = listState.firstVisibleItemIndex, pageCount = pageCount,
            title = title, onSettings = onSettings, onSeek = jump, onDismiss = { chrome = false },
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
            // Either way up: a phone turned left or right is landscape all the same.
            RotationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
    /** Fires once when the page's border crop is decided (or null if uncropped / crop disabled). */
    onCropDecided: ((CropRect?) -> Unit)? = null,
    /** Fires once per load with this page's sampled edge colour (§4, §5.2, milestone 5). */
    onBackgroundColour: (Color) -> Unit = {},
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
    val img = image?.takeIf { it.width > 0 && it.height > 0 }
    val screen = LocalConfiguration.current
    val fit = fitMode ?: img?.let {
        prefs.fitFor(FitContext.of(screen.screenWidthDp, screen.screenHeightDp, it.width, it.height))
    } ?: prefs.fitMode
    PageSlotContent(
        img = img, loading = loading, fit = fit, bookId = bookId, index = index,
        vm = vm, rightToLeft = rightToLeft, pagerVertical = pagerVertical,
        onPagerLockChanged = onPagerLockChanged, onEdgeSwipe = onEdgeSwipe, onTapZone = onTapZone,
        spreadSide = spreadSide, onBaseReady = onBaseReady, zoomSteps = zoomSteps,
        onCropDecided = onCropDecided, onBackgroundColour = onBackgroundColour,
        onInvalidate = { vm.invalidatePage(index); attempts++ },
    )
}

/**
 * The three-way content switch for one reader page — decoded surface, loading spinner, or
 * unreadable-page state with retry. Extracted from [PageSlot] so that function stays within
 * detekt's 60-line LongMethod limit (the PageCanvas call alone was ~15 of those lines). No
 * behavioural change.
 */
@Composable
private fun PageSlotContent(
    img: PageImage?,
    loading: Boolean,
    fit: FitMode,
    bookId: String,
    index: Int,
    vm: ReaderViewModel,
    rightToLeft: Boolean,
    pagerVertical: Boolean,
    onPagerLockChanged: (Boolean) -> Unit,
    onEdgeSwipe: (Boolean) -> Unit,
    onTapZone: (TapZone) -> Unit,
    spreadSide: SpreadSide,
    onBaseReady: () -> Unit,
    zoomSteps: Flow<Float>?,
    onCropDecided: ((CropRect?) -> Unit)?,
    onBackgroundColour: (Color) -> Unit,
    onInvalidate: () -> Unit,
) {
    // Draw-observed colour state for §4. The colour is NEVER read in composition: one
    // lifecycle-aware collector writes it into draw-observed state, so a slider drag
    // repaints at 120 Hz without recomposing PageCanvas (or any sibling slot).
    val lifecycleOwner = LocalLifecycleOwner.current
    val colourState = remember { mutableStateOf(ColourParams.NEUTRAL) }
    LaunchedEffect(vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.renderingPrefs.collect { colourState.value = it.colour }
        }
    }
    // Upscaler recomposes only on a real switch: distinctUntilChanged keeps a colour
    // change (a new RenderingPrefs instance) from recomposing the slot through this read.
    // The operators run inside remember, not composition (FlowOperatorInvokedInComposition).
    val upscalerFlow = remember(vm) {
        vm.renderingPrefs.map { it.upscaler }.distinctUntilChanged()
    }
    val upscaler by upscalerFlow.collectAsStateWithLifecycle(initialValue = Upscaler.PLATFORM)
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
            onCropDecided = onCropDecided,
            onBackgroundColour = onBackgroundColour,
            colour = colourState,
            upscaler = upscaler,
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
                Button(onClick = onInvalidate) {
                    Text(stringResource(R.string.reader_retry))
                }
            }
        }
    }
}
