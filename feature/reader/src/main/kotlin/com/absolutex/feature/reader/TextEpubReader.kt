package com.absolutex.feature.reader

import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.net.Uri
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.hilt.navigation.compose.hiltViewModel
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The reader for a reflowable text EPUB (§2): the book's own chapters in a WebView, one screen
 * at a time.
 *
 * Chapters are served from the archive through [BookClient] at a private origin, so their own
 * stylesheets and images resolve and nothing reaches the network. Each chapter gets a dark
 * stylesheet that lays it out in screen-wide columns; a page turn scrolls one column. Crossing a
 * chapter's end loads the next one, entering the previous chapter lands on its last page.
 *
 * Paging, gestures are Compose's, over the WebView: the side thirds turn pages, the middle toggles
 * the chrome, a drag moves the page with the finger. Scrolling (the reader's other mode), a chapter
 * is one long page the WebView scrolls itself, with previous/next chapter links at its ends.
 */
@Composable
internal fun TextEpubReader(
    uri: Uri,
    title: String,
    onSettings: (() -> Unit)?,
    vm: TextEpubViewModel = hiltViewModel(),
) {
    val opened by produceState<Pair<TextEpubBook, Int>?>(null, uri) { value = vm.open(uri) }
    val current = opened
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (current == null) {
            CircularProgressIndicator()
        } else {
            TextBook(current.first, current.second, title, onSettings, vm)
        }
    }
}

@Composable
private fun TextBook(
    book: TextEpubBook,
    startChapter: Int,
    title: String,
    onSettings: (() -> Unit)?,
    vm: TextEpubViewModel,
) {
    var chapter by rememberSaveable { mutableIntStateOf(startChapter) }
    var fontPx by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_PX) }
    var chrome by rememberSaveable { mutableStateOf(false) }
    // Pages turn like a book; scroll reads a chapter as one long page (the user's choice).
    var scroll by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val web = rememberBookWebView(book)
    val pager = remember(book, web) { ChapterPager(scope, web).also { p -> web.onMeasured = p::measured } }
    val labels = ChapterLinks(
        stringResource(R.string.reader_text_prev_chapter),
        stringResource(R.string.reader_text_next_chapter),
    )

    // One place that decides what a turn means, so taps, swipes and chapter edges agree.
    val leaveChapter: (Boolean) -> Unit = { forward ->
        when {
            forward && chapter < book.spine.size - 1 -> chapter += 1
            !forward && chapter > 0 -> {
                pager.landOnLastPage = true
                chapter -= 1
            }
            else -> haptics.confirm() // the book's first or last page: nothing further to turn to
        }
    }
    val turn: (Boolean) -> Unit = { forward ->
        when {
            forward && pager.page < pager.pages - 1 -> pager.slideTo(pager.page + 1)
            !forward && pager.page > 0 -> pager.slideTo(pager.page - 1)
            else -> leaveChapter(forward)
        }
    }
    web.scrollMode = scroll
    web.onTap = { chrome = !chrome }
    web.onChapterLink = leaveChapter
    DisposableEffect(chapter, fontPx, scroll) {
        pager.load(book.spine[chapter], PageBox(fontPx, 0, 0, scroll, labels))
        vm.saveChapter(book, chapter)
        onDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        StatusBarStrip()
        // Scrolling, the WebView takes its own touches (see BookWebView); paging, Compose does.
        if (!scroll) {
            TurnGestures(pager, onTurn = turn, onLeaveChapter = leaveChapter, onMiddle = { chrome = !chrome })
        }
        TextChrome(chrome, title, onSettings) {
            TextBottomBar(
                chapter = chapter,
                chapters = book.spine.size,
                page = pager.page,
                pages = pager.pages,
                scroll = scroll,
                onToggleScroll = { scroll = !scroll },
                onSmaller = { fontPx = (fontPx - FONT_STEP_PX).coerceAtLeast(MIN_FONT_PX) },
                onLarger = { fontPx = (fontPx + FONT_STEP_PX).coerceAtMost(MAX_FONT_PX) },
            )
        }
    }
}

/** A solid strip behind the status bar: scrolling text passed under the clock and icons. */
@Composable
private fun BoxScope.StatusBarStrip() {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Color.Black),
    )
}

/** The top bar and [bottom], sliding in from their own edges while [shown]. */
@Composable
private fun BoxScope.TextChrome(
    shown: Boolean,
    title: String,
    onSettings: (() -> Unit)?,
    bottom: @Composable () -> Unit,
) {
    AnimatedVisibility(
        shown,
        enter = slideInVertically(Motion.enter()) { -it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.TopCenter),
    ) { ReaderTopBar(title, onSettings) }
    AnimatedVisibility(
        shown,
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) { bottom() }
}

/**
 * Taps in the side thirds turn, the middle toggles the chrome, and a horizontal drag moves the
 * page with the finger, settling on release through [ChapterPager.release].
 */
@Composable
private fun TurnGestures(
    pager: ChapterPager,
    onTurn: (Boolean) -> Unit,
    onLeaveChapter: (Boolean) -> Unit,
    onMiddle: () -> Unit,
) {
    val turn by rememberUpdatedState(onTurn)
    val leave by rememberUpdatedState(onLeaveChapter)
    val middle by rememberUpdatedState(onMiddle)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    when {
                        at.x < size.width / THIRDS -> turn(false)
                        at.x > size.width * 2 / THIRDS -> turn(true)
                        else -> middle()
                    }
                }
            }
            .pointerInput(pager) {
                var travel = 0f
                val velocity = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        travel = 0f
                        velocity.resetTracking()
                    },
                    onDragEnd = { pager.release(travel, velocity.calculateVelocity().x)?.let(leave) },
                    onDragCancel = { pager.slideTo(pager.page) },
                ) { change, dx ->
                    travel += dx
                    velocity.addPosition(change.uptimeMillis, change.position)
                    pager.dragBy(dx)
                }
            },
    )
}

@Composable
private fun TextBottomBar(
    chapter: Int,
    chapters: Int,
    page: Int,
    pages: Int,
    scroll: Boolean,
    onToggleScroll: () -> Unit,
    onSmaller: () -> Unit,
    onLarger: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
            bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (scroll) {
                    stringResource(R.string.reader_text_chapter, chapter + 1, chapters)
                } else {
                    stringResource(
                        R.string.reader_text_position,
                        chapter + 1,
                        chapters,
                        page + 1,
                        pages.coerceAtLeast(1),
                    )
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = onToggleScroll, modifier = Modifier.size(A11y.MinTouchTarget)) {
                Icon(
                    if (scroll) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.SwapVert,
                    contentDescription = stringResource(
                        if (scroll) R.string.reader_text_pages else R.string.reader_text_scroll,
                    ),
                )
            }
            FilledTonalIconButton(onClick = onSmaller, modifier = Modifier.size(A11y.MinTouchTarget)) {
                Icon(Icons.Outlined.TextDecrease, contentDescription = stringResource(R.string.reader_text_smaller))
            }
            FilledTonalIconButton(onClick = onLarger, modifier = Modifier.size(A11y.MinTouchTarget)) {
                Icon(Icons.Outlined.TextIncrease, contentDescription = stringResource(R.string.reader_text_larger))
            }
        }
    }
}

/**
 * Where the reader is inside the loaded chapter, and every move within it. Pages are columns one
 * WebView-width apart, and moving between them scrolls the WebView natively: a drag moves the
 * page with the finger frame by frame, a release or a tap animates to the chosen page. A chapter
 * fades in once it has laid out, rather than popping from blank.
 *
 * [landOnLastPage] makes a backwards chapter change arrive at the end of the previous chapter.
 */
private class ChapterPager(private val scope: CoroutineScope, private val web: WebView) {
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(1)
    var landOnLastPage = false
    private var motion: Job? = null

    /**
     * One page in physical pixels, computed exactly as the chapter's columns are laid out (the CSS
     * page width times the density), so page N never drifts from where its column really is.
     */
    private fun stride(): Int {
        val cssWidth = (web.tag as? PageBox)?.width ?: 0
        val exact = (cssWidth * web.resources.displayMetrics.density).roundToInt()
        return if (exact > 0) exact else web.width
    }

    fun load(entry: String, box: PageBox) {
        motion?.cancel()
        web.animate().cancel()
        web.alpha = 0f
        // Only once the view has its real size: the page box is given to the chapter in CSS
        // pixels from here, never measured by the page (see readingCss).
        web.doOnLayout { view ->
            val density = view.resources.displayMetrics.density
            view.tag = box.copy(width = (view.width / density).toInt(), height = (view.height / density).toInt())
            (view as WebView).loadUrl(ORIGIN + entry.split('/').joinToString("/") { Uri.encode(it) })
        }
    }

    /** Called when a chapter has laid out: count its pages, land on the first or the last. */
    fun measured(count: Int) {
        val scrolling = (web.tag as? PageBox)?.scroll == true
        pages = if (scrolling) 1 else count.coerceAtLeast(1)
        page = if (landOnLastPage) pages - 1 else 0
        if (scrolling) {
            // Going back a chapter while scrolling lands at its end, as paging does.
            val y = if (landOnLastPage) "document.body.scrollHeight" else "0"
            web.evaluateJavascript("window.scrollTo(0, $y)", null)
        } else {
            web.scrollTo(page * stride(), 0)
        }
        landOnLastPage = false
        web.animate().alpha(1f).setDuration(Motion.MEDIUM_MS.toLong()).start()
    }

    /** Follows the finger, never past the chapter's first or last page. */
    fun dragBy(dx: Float) {
        motion?.cancel()
        val max = (pages - 1) * stride()
        web.scrollTo((web.scrollX - dx).toInt().coerceIn(0, max), 0)
    }

    /**
     * Where a released drag settles: the next or previous page when it travelled far enough or
     * was flung, else back where it started. A drag outward from the chapter's first or last page
     * returns the direction to leave the chapter in, for the caller to load the neighbour.
     */
    fun release(travel: Float, velocity: Float): Boolean? {
        val forward = travel < 0
        val committed = abs(travel) > stride() * COMMIT_FRACTION || abs(velocity) > FLING_PX_PER_S
        val target = if (committed) page + (if (forward) 1 else -1) else page
        if (target !in 0 until pages) {
            slideTo(page)
            return forward
        }
        slideTo(target)
        return null
    }

    fun slideTo(target: Int) {
        page = target
        motion?.cancel()
        val from = web.scrollX.toFloat()
        motion = scope.launch {
            val spec = tween<Float>(Motion.MEDIUM_MS, easing = Motion.Emphasized)
            animate(from, (target * stride()).toFloat(), animationSpec = spec) { x, _ -> web.scrollTo(x.toInt(), 0) }
        }
    }
}
