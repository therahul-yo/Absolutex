package com.absolutex.feature.reader

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
    val pager = remember(book) { ChapterPager() }
    val haptics = rememberHaptics()
    val web = rememberBookWebView(book, pager)

    // One place that decides what a turn means, so taps, swipes and chapter edges agree.
    val turn: (Boolean) -> Unit = { forward ->
        when {
            forward && pager.page < pager.pages - 1 -> pager.show(web, pager.page + 1)
            !forward && pager.page > 0 -> pager.show(web, pager.page - 1)
            forward && chapter < book.spine.size - 1 -> chapter += 1
            !forward && chapter > 0 -> {
                pager.landOnLastPage = true
                chapter -= 1
            }
            else -> haptics.confirm() // the book's first or last page: nothing further to turn to
        }
    }
    DisposableEffect(chapter, fontPx) {
        pager.load(web, book.spine[chapter], fontPx)
        vm.saveChapter(book, chapter)
        onDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        TurnGestures(onTurn = turn, onMiddle = { chrome = !chrome })
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

/** Taps in the side thirds turn, the middle toggles the chrome, and a swipe turns its way. */
@Composable
private fun TurnGestures(onTurn: (Boolean) -> Unit, onMiddle: () -> Unit) {
    val turn by rememberUpdatedState(onTurn)
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
            .pointerInput(Unit) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (abs(travel) > SWIPE_PX) turn(travel < 0) },
                ) { _, delta -> travel += delta }
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
 * Where the reader is inside the loaded chapter, and the moves within it. [pages] is measured by
 * the page's own script once the chapter lays out; [landOnLastPage] makes a backwards chapter
 * change arrive at the end of the previous chapter rather than its start.
 */
private class ChapterPager {
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(1)
    var landOnLastPage = false

    fun load(web: WebView, entry: String, fontPx: Int) {
        web.tag = fontPx
        web.loadUrl(ORIGIN + entry.split('/').joinToString("/") { Uri.encode(it) })
    }

    fun show(web: WebView, target: Int) {
        page = target
        web.evaluateJavascript("window.scrollTo({left: $target * window.innerWidth, behavior: 'smooth'});", null)
    }

    /** Called when a chapter has laid out: count its pages and land on the first or the last. */
    fun measured(web: WebView, count: Int) {
        pages = count.coerceAtLeast(1)
        val target = if (landOnLastPage) pages - 1 else 0
        landOnLastPage = false
        page = target
        web.evaluateJavascript("window.scrollTo(${target} * window.innerWidth, 0);", null)
    }
}

@Composable
private fun rememberBookWebView(book: TextEpubBook, pager: ChapterPager): WebView {
    val context = LocalContext.current
    val web = remember(book) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            // Script only for measuring and turning pages; the book's own is the book's.
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Gestures are Compose's (TurnGestures); the WebView only draws.
            setOnTouchListener { _, _ -> true }
            webViewClient = BookClient(book) { view, count -> pager.measured(view, count) }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    return web
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
        val body = if (type.contains("html")) withReadingStyle(bytes, fontPxOf(view)) else bytes
        return WebResourceResponse(type, "UTF-8", ByteArrayInputStream(body))
    }

    override fun onPageFinished(view: WebView, url: String) {
        // After fonts and images settle a little, so the count is the laid-out count.
        view.postDelayed({
            view.evaluateJavascript(MEASURE_JS) { result -> onMeasured(view, result.trim('"').toIntOrNull() ?: 1) }
        }, LAYOUT_SETTLE_MS)
    }

    private fun fontPxOf(view: WebView): Int = (view.tag as? Int) ?: DEFAULT_FONT_PX

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
private fun withReadingStyle(bytes: ByteArray, fontPx: Int): ByteArray {
    val html = String(bytes, Charsets.UTF_8)
    val style = "<style>${readingCss(fontPx)}</style>"
    val at = html.lastIndexOf("</head>", ignoreCase = true)
    val out = if (at >= 0) html.substring(0, at) + style + html.substring(at) else style + html
    return out.toByteArray(Charsets.UTF_8)
}

/**
 * Dark, calm and paginated: the body is one screen tall and flows into screen-wide columns, so
 * the page stride is exactly the window width. Colours are forced over the book's own, which are
 * almost always black on white.
 */
private fun readingCss(fontPx: Int) = """
    html { height: 100%; overflow: hidden; background: #000 !important; }
    body {
        margin: 0 !important; height: 100vh; box-sizing: border-box;
        padding: 56px ${SIDE_PX}px 48px ${SIDE_PX}px !important;
        column-width: calc(100vw - ${SIDE_PX * 2}px); column-gap: ${SIDE_PX * 2}px; column-fill: auto;
        background: #000 !important; color: #E6E6E6 !important;
        font-size: ${fontPx}px !important; line-height: 1.6 !important;
        font-family: sans-serif; -webkit-text-size-adjust: none;
    }
    body * { color: inherit !important; background-color: transparent !important; max-width: 100%; }
    a { color: #BDBDBD !important; }
    img, svg, image { max-height: 85vh; object-fit: contain; height: auto; }
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
private const val MEASURE_JS = "Math.max(1, Math.round(document.documentElement.scrollWidth / window.innerWidth))"
private const val LAYOUT_SETTLE_MS = 120L
private const val DEFAULT_FONT_PX = 18
private const val MIN_FONT_PX = 12
private const val MAX_FONT_PX = 32
private const val FONT_STEP_PX = 2
private const val SIDE_PX = 22
private const val THIRDS = 3
private const val SWIPE_PX = 60f
private const val HTTP_NOT_FOUND = 404
