package com.absolutex.feature.reader

import androidx.core.view.doOnLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.absolutex.core.ui.A11y
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The reader for a reflowable text EPUB (§2): the book's own chapters in a WebView, one screen
 * at a time.
 *
 * Chapters are served from the archive through [BookClient] at a private origin, so their own
 * stylesheets and images resolve and nothing reaches the network. Each chapter gets a dark
 * stylesheet that lays it out in screen-wide columns; a page turn scrolls one column. Crossing a
 * chapter's end loads the next one, entering the previous chapter lands on its last page.
 *
 * Gestures are Compose's, over the WebView (which takes no touches): the side thirds turn pages,
 * the middle toggles the chrome, a horizontal swipe turns in its direction.
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
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val web = rememberBookWebView(book)
    val pager = remember(book, web) { ChapterPager(scope, web).also { p -> web.onMeasured = p::measured } }

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
    DisposableEffect(chapter, fontPx) {
        pager.load(book.spine[chapter], fontPx)
        vm.saveChapter(book, chapter)
        onDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        TurnGestures(pager, onTurn = turn, onLeaveChapter = leaveChapter, onMiddle = { chrome = !chrome })
        AnimatedVisibility(
            chrome,
            enter = slideInVertically(Motion.enter()) { -it } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) { ReaderTopBar(title, onSettings) }
        AnimatedVisibility(
            chrome,
            enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TextBottomBar(
                chapter = chapter,
                chapters = book.spine.size,
                page = pager.page,
                pages = pager.pages,
                onSmaller = { fontPx = (fontPx - FONT_STEP_PX).coerceAtLeast(MIN_FONT_PX) },
                onLarger = { fontPx = (fontPx + FONT_STEP_PX).coerceAtMost(MAX_FONT_PX) },
            )
        }
    }
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
                stringResource(R.string.reader_text_position, chapter + 1, chapters, page + 1, pages.coerceAtLeast(1)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
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

    fun load(entry: String, fontPx: Int) {
        motion?.cancel()
        web.animate().cancel()
        web.alpha = 0f
        // Only once the view has its real size: the page box is given to the chapter in CSS
        // pixels from here, never measured by the page (see readingCss).
        web.doOnLayout { view ->
            val density = view.resources.displayMetrics.density
            view.tag = PageBox(fontPx, (view.width / density).toInt(), (view.height / density).toInt())
            (view as WebView).loadUrl(ORIGIN + entry.split('/').joinToString("/") { Uri.encode(it) })
        }
    }

    /** Called when a chapter has laid out: count its pages, land on the first or the last. */
    fun measured(count: Int) {
        pages = count.coerceAtLeast(1)
        page = if (landOnLastPage) pages - 1 else 0
        landOnLastPage = false
        web.scrollTo(page * stride(), 0)
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

@Composable
private fun rememberBookWebView(book: TextEpubBook): BookWebView {
    val context = LocalContext.current
    val web = remember(book) {
        BookWebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            // Script only for measuring and turning pages; the book's own is the book's.
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            // Font size is the reader's (A-/A+), never the system's text boosting.
            settings.textZoom = FULL_TEXT_ZOOM
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Gestures are Compose's (TurnGestures); the WebView only draws.
            setOnTouchListener { _, _ -> true }
            webViewClient = BookClient(book) { view, count -> (view as BookWebView).onMeasured(count) }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    return web
}

/** A WebView that tells its pager when a chapter has been measured. */
private class BookWebView(context: android.content.Context) : WebView(context) {
    var onMeasured: (Int) -> Unit = {}
}

/**
 * Serves the book's entries at [ORIGIN] and nothing else: every other request gets an empty 404,
 * so a chapter cannot reach the network. Chapters (HTML and XHTML) get the reading stylesheet
 * and the measuring script spliced in before their closing head tag.
 */
private class BookClient(
    private val book: TextEpubBook,
    private val onMeasured: (WebView, Int) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        if (!url.startsWith(ORIGIN)) return notFound()
        val entry = Uri.decode(url.removePrefix(ORIGIN).substringBefore('#').substringBefore('?'))
        val bytes = book.read(entry) ?: return notFound()
        val type = mimeOf(entry)
        val body = if (type.contains("html")) withReadingStyle(bytes, boxOf(view)) else bytes
        return WebResourceResponse(type, "UTF-8", ByteArrayInputStream(body))
    }

    override fun onPageFinished(view: WebView, url: String) {
        // After fonts and images settle a little, so the count is the laid-out count.
        view.postDelayed({
            view.evaluateJavascript(measureJs(boxOf(view).width)) { result ->
                onMeasured(view, result.trim('"').toIntOrNull() ?: 1)
            }
        }, LAYOUT_SETTLE_MS)
    }

    private fun boxOf(view: WebView): PageBox = (view.tag as? PageBox) ?: PageBox(DEFAULT_FONT_PX, 0, 0)

    private fun notFound() =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            HTTP_NOT_FOUND,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}

/** The chapter's own markup with the reading stylesheet added last, so it wins. */
private fun withReadingStyle(bytes: ByteArray, box: PageBox): ByteArray {
    val html = String(bytes, Charsets.UTF_8)
    // A mobile viewport: without it the WebView lays the chapter out as a ~1400 px desktop page
    // and boosts the text to compensate, and the columns come out a screen and a half wide.
    val style = "<meta name=\"viewport\" content=\"$VIEWPORT\"/><style>${readingCss(box)}</style>"
    val at = html.lastIndexOf("</head>", ignoreCase = true)
    val out = if (at >= 0) html.substring(0, at) + style + html.substring(at) else style + html
    return out.toByteArray(Charsets.UTF_8)
}

/**
 * Dark, calm and paginated: the body is one screen tall and flows into screen-wide columns, so
 * the page stride is exactly the window width. Colours are forced over the book's own, which are
 * almost always black on white.
 *
 * The page box comes from the app, in CSS pixels, never from the page: vw/vh are zero before the
 * WebView is laid out, and the window's own width grows to fit the very columns that overflow
 * it — measuring it fed the columns back into their own width.
 */
private fun readingCss(box: PageBox) = """
    :root { --w: ${box.width}px; --h: ${box.height}px; }
    html {
        height: 100% !important; width: 100% !important;
        /* Scrollable sideways (by the app, never by touch) so the WebView's own scroll moves
           between columns; hidden overflow pinned the page to its first column. */
        overflow-x: scroll !important; overflow-y: hidden !important; scrollbar-width: none;
        margin: 0 !important; padding: 0 !important; background: #000 !important;
        display: block !important; position: static !important;
    }
    body {
        display: block !important; position: static !important; float: none !important;
        width: auto !important; max-width: none !important; min-height: 0 !important;
        margin: 0 !important; height: var(--h, 100vh) !important; box-sizing: border-box !important;
        padding: ${TOP_PX}px ${SIDE_PX}px ${BOTTOM_PX}px ${SIDE_PX}px !important;
        column-width: calc(var(--w, 100vw) - ${SIDE_PX * 2}px) !important;
        column-gap: ${SIDE_PX * 2}px !important; column-fill: auto !important; column-count: auto !important;
        overflow: visible !important;
        background: #000 !important; color: #E6E6E6 !important;
        font-size: ${box.fontPx}px !important; line-height: 1.6 !important;
        font-family: sans-serif; -webkit-text-size-adjust: 100% !important; text-size-adjust: 100% !important;
    }
    ::-webkit-scrollbar { display: none; }
    body * { color: inherit !important; background-color: transparent !important; max-width: 100%; }
    /* A book's own layout can hold a block together (a table of contents in one list, a fixed
       height, flex): in columns that block then runs off the page instead of onto the next. */
    body *:not(img):not(svg):not(image) {
        break-inside: auto !important; height: auto !important; max-height: none !important;
        overflow: visible !important; position: static !important;
    }
    div, section, nav, article, header, footer, aside, main { display: block !important; }
    a { color: #BDBDBD !important; }
    img, svg, image {
        max-height: calc(var(--h, 100vh) - ${TOP_PX + BOTTOM_PX}px) !important; object-fit: contain; height: auto;
    }
    p { orphans: 2; widows: 2; }
    h1, h2, h3 { line-height: 1.25 !important; break-after: avoid; }
""".trimIndent()

private fun mimeOf(entry: String): String = when (entry.substringAfterLast('.').lowercase()) {
    "xhtml", "xht" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "otf", "ttf" -> "font/ttf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/** A private origin: https so the WebView treats it as secure, a reserved name so it is never real. */
private const val ORIGIN = "https://book.absolutex.invalid/"
/** Counts the columns the chapter produced, one page each, at the page width the app gave it. */
private fun measureJs(pageWidth: Int) =
    "Math.max(1, Math.ceil((Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - 2) / $pageWidth))"

/** The chapter's page box in CSS pixels, and its text size. */
private data class PageBox(val fontPx: Int, val width: Int, val height: Int)

/** A fixed, unscalable viewport: the layout width must not grow to fit the overflowing columns. */
private const val VIEWPORT = "width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no"
private const val LAYOUT_SETTLE_MS = 120L
private const val FULL_TEXT_ZOOM = 100
private const val DEFAULT_FONT_PX = 18
private const val MIN_FONT_PX = 12
private const val MAX_FONT_PX = 32
private const val FONT_STEP_PX = 2
private const val SIDE_PX = 22
private const val TOP_PX = 56

/** Clear of the gesture bar, which the page draws under. */
private const val BOTTOM_PX = 88
private const val THIRDS = 3
/** How far a drag must travel, as a share of the page, to turn it on release. */
private const val COMMIT_FRACTION = 0.2f
private const val FLING_PX_PER_S = 800f
private const val HTTP_NOT_FOUND = 404
