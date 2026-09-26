package com.absolutex.feature.reader

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import com.absolutex.model.TocEntry
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
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
    val opened by produceState<Pair<TextEpubBook, TextResume>?>(null, uri) { value = vm.open(uri) }
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
    resume: TextResume,
    title: String,
    onSettings: (() -> Unit)?,
    vm: TextEpubViewModel,
) {
    var chapter by rememberSaveable { mutableIntStateOf(resume.chapter) }
    var fontPx by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_PX) }
    var chrome by rememberSaveable { mutableStateOf(false) }
    var contents by rememberSaveable { mutableStateOf(false) }
    // Pages turn like a book; scroll reads a chapter as one long page (the user's choice).
    var scroll by rememberSaveable { mutableStateOf(resume.scroll) }
    val haptics = rememberHaptics()
    val web = rememberBookWebView(book)
    val pager = rememberChapterPager(web, resume.fraction)
    val labels = chapterLinks()

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
    val turn: (Boolean) -> Unit = { pager.turn(it, leaveChapter) }
    web.scrollMode = scroll
    web.onTap = { chrome = !chrome }
    web.onChapterLink = leaveChapter
    LoadAndRemember(book, pager, PageBox(fontPx, 0, 0, scroll, labels), chapter, vm)
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        StatusBarStrip()
        // Scrolling, the WebView takes its own touches (see BookWebView); paging, Compose does.
        if (!scroll) {
            TurnGestures(pager, onTurn = turn, onLeaveChapter = leaveChapter, onMiddle = { chrome = !chrome })
        }
        TextChrome(chrome, title, book.identity, onSettings) {
            FindAndContents(
                book, chapter, contents,
                onChapter = { chapter = it; contents = false },
                onHit = { hit, pattern ->
                    contents = false
                    chrome = false
                    pager.show(SearchMark(hit.chapter, pattern, hit.match.occurrence))
                    chapter = hit.chapter
                },
            )
            TextBottomBar(
                hasContents = book.toc.isNotEmpty(),
                onContents = { contents = !contents },
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

/**
 * Loads [chapter] with [box] whenever either changes, and keeps the reader's place: the chapter in
 * the shared progress, the fraction through it and the pages/scroll choice in the reader's own.
 */
@Composable
private fun LoadAndRemember(
    book: TextEpubBook,
    pager: ChapterPager,
    box: PageBox,
    chapter: Int,
    vm: TextEpubViewModel,
) {
    DisposableEffect(chapter, box.fontPx, box.scroll) {
        pager.load(chapter, book.spine[chapter], box)
        vm.saveChapter(book, chapter)
        vm.saveScroll(box.scroll)
        onDispose { vm.savePosition(book, chapter, pager.fraction()) }
    }
    LaunchedEffect(chapter, pager.page) {
        vm.savePosition(book, chapter, pager.fraction())
        vm.recordPage(book, chapter, pager.page)
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
    bookId: String,
    onSettings: (() -> Unit)?,
    bottom: @Composable () -> Unit,
) {
    AnimatedVisibility(
        shown,
        enter = slideInVertically(Motion.enter()) { -it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.TopCenter),
    ) { ReaderTopBar(title, onSettings, bookId = bookId) }
    AnimatedVisibility(
        shown,
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
        // Above the keyboard while the search field has it, never behind it.
    ) { Column(Modifier.imePadding()) { bottom() } }
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

/** The pager for [web], reporting its measurements to it, resuming [fraction] into the first chapter. */
@Composable
private fun rememberChapterPager(web: BookWebView, fraction: Float): ChapterPager {
    val scope = rememberCoroutineScope()
    return remember(web) {
        ChapterPager(scope, web).also { pager ->
            web.onMeasured = pager::measured
            pager.resumeAt = fraction
        }
    }
}

/** The previous/next chapter links a scrolled chapter ends with, in the reader's language. */
@Composable
private fun chapterLinks() = ChapterLinks(
    stringResource(R.string.reader_text_prev_chapter),
    stringResource(R.string.reader_text_next_chapter),
)

@Composable
private fun TextBottomBar(
    hasContents: Boolean,
    onContents: () -> Unit,
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
            FilledTonalIconButton(onClick = onContents, modifier = Modifier.size(A11y.MinTouchTarget)) {
                Icon(
                    if (hasContents) Icons.AutoMirrored.Outlined.List else Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.reader_text_find),
                )
            }
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
/** A search hit to show: its chapter, the pattern to find it with, and which match it is. */
internal data class SearchMark(val chapter: Int, val pattern: String, val occurrence: Int)

private class ChapterPager(private val scope: CoroutineScope, private val web: WebView) {
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(1)
    var landOnLastPage = false

    /** How far into the next laid-out chapter to land, as a fraction; null lands at its start. */
    var resumeAt: Float? = null
    private var loadedChapter = -1
    private var measuredChapter = -1

    /** A search hit to highlight once its chapter has laid out. */
    private var pendingMark: SearchMark? = null
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

    /**
     * How far through the chapter the reader is: the page's share of the chapter, or, scrolling,
     * the scroll's share of its height.
     */
    fun fraction(): Float {
        val scrolling = (web.tag as? PageBox)?.scroll == true
        if (!scrolling) return if (pages <= 1) 0f else page.toFloat() / (pages - 1)
        val range = web.contentHeight * web.resources.displayMetrics.density - web.height
        return if (range <= 0f) 0f else (web.scrollY / range).coerceIn(0f, 1f)
    }

    fun load(chapter: Int, entry: String, box: PageBox) {
        // Reloading the same chapter (text size, pages/scroll) keeps the place in it.
        if (chapter == loadedChapter && resumeAt == null) resumeAt = fraction()
        loadedChapter = chapter
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
        // Going back a chapter lands at its end, as a book does; a resume lands where it left off.
        val at = if (landOnLastPage) 1f else resumeAt ?: 0f
        page = (at * (pages - 1)).roundToInt()
        if (scrolling) {
            web.evaluateJavascript("window.scrollTo(0, $at * (document.body.scrollHeight - innerHeight))", null)
        } else {
            web.scrollTo(page * stride(), 0)
        }
        landOnLastPage = false
        resumeAt = null
        measuredChapter = loadedChapter
        // A search hit lands on its own page, and the chapter fades in there, not on page one first.
        val mark = pendingMark?.takeIf { it.chapter == loadedChapter }
        pendingMark = null
        if (mark != null) highlight(mark, fadeIn) else fadeIn()
    }

    private val fadeIn: () -> Unit = { web.animate().alpha(1f).setDuration(Motion.MEDIUM_MS.toLong()).start() }

    /** A page on, or back; past the chapter's first or last page, [leave] loads the neighbour. */
    fun turn(forward: Boolean, leave: (Boolean) -> Unit) {
        when {
            forward && page < pages - 1 -> slideTo(page + 1)
            !forward && page > 0 -> slideTo(page - 1)
            else -> leave(forward)
        }
    }

    /** Shows [mark]: now, when its chapter is the one laid out, else once that chapter has laid out. */
    fun show(mark: SearchMark) {
        val laidOut = mark.chapter == loadedChapter && measuredChapter == loadedChapter
        if (laidOut) highlight(mark) {} else pendingMark = mark
    }

    /** Highlights the hit and moves to it: its page when paging, a third down the screen when scrolling. */
    private fun highlight(mark: SearchMark, then: () -> Unit) {
        val box = web.tag as? PageBox
        val scrolling = box?.scroll == true
        web.evaluateJavascript(markJs(mark.pattern, mark.occurrence, box?.width ?: 1, scrolling)) { result ->
            val at = result?.toFloatOrNull() ?: -1f
            when {
                at < 0f -> Unit
                scrolling -> web.evaluateJavascript("window.scrollTo(0, $at)", null)
                else -> {
                    page = at.toInt().coerceIn(0, pages - 1)
                    web.scrollTo(page * stride(), 0)
                }
            }
            then()
        }
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
