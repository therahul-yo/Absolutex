package com.absolutex.feature.reader

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayInputStream
import org.json.JSONObject

@Composable
internal fun rememberBookWebView(book: TextEpubBook): BookWebView {
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
            // Paging, gestures are Compose's (TurnGestures) and the WebView only draws. Scrolling,
            // it scrolls itself, and a single tap still toggles the chrome.
            val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true.also { onTap() }
            })
            setOnTouchListener { _, event ->
                if (scrollMode) taps.onTouchEvent(event)
                !scrollMode
            }
            webViewClient = BookClient(book) { view, count -> (view as BookWebView).onMeasured(count) }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    return web
}

/** A WebView that reports back to its reader: a chapter measured, a tap, a chapter link. */
internal class BookWebView(context: android.content.Context) : WebView(context) {
    var onMeasured: (Int) -> Unit = {}
    var onTap: () -> Unit = {}
    var onChapterLink: (Boolean) -> Unit = {}
    var scrollMode = false
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

    /** The chapter links a scrolling chapter ends with; every other navigation is refused. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        when (request.url.toString()) {
            ORIGIN + NEXT_LINK -> (view as BookWebView).onChapterLink(true)
            ORIGIN + PREV_LINK -> (view as BookWebView).onChapterLink(false)
        }
        return true
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
    var out = if (at >= 0) html.substring(0, at) + style + html.substring(at) else style + html
    if (box.scroll) out = withChapterLinks(out, box.links)
    return out.toByteArray(Charsets.UTF_8)
}

/** A scrolling chapter opens with a link back and closes with a link on, as buttons. */
private fun withChapterLinks(html: String, links: ChapterLinks?): String {
    links ?: return html
    val prev = "<p class=\"abx-nav\"><a href=\"$ORIGIN$PREV_LINK\">${links.previous}</a></p>"
    val next = "<p class=\"abx-nav\"><a href=\"$ORIGIN$NEXT_LINK\">${links.next}</a></p>"
    val open = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html)
    val withPrev = open?.let { html.substring(0, it.range.last + 1) + prev + html.substring(it.range.last + 1) } ?: html
    val close = withPrev.lastIndexOf("</body>", ignoreCase = true)
    return if (close >= 0) withPrev.substring(0, close) + next + withPrev.substring(close) else withPrev + next
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
private fun readingCss(box: PageBox) = if (box.scroll) scrollCss(box) else pagedCss(box)

/** Scrolling: one long, calm column, and the chapter links as full-width buttons. */
private fun scrollCss(box: PageBox) = """
    html { overflow-x: hidden !important; overflow-y: auto !important; background: #000 !important; }
    body {
        margin: 0 !important; height: auto !important; box-sizing: border-box !important;
        padding: ${TOP_PX}px ${SIDE_PX}px ${BOTTOM_PX}px ${SIDE_PX}px !important;
        background: #000 !important; color: #E6E6E6 !important;
        font-size: ${box.fontPx}px !important; line-height: 1.6 !important;
        font-family: sans-serif; -webkit-text-size-adjust: 100% !important;
    }
    ::-webkit-scrollbar { display: none; }
    body * { color: inherit !important; background-color: transparent !important; max-width: 100%; }
    a { color: #BDBDBD !important; }
    ${MARK_CSS}
    img, svg, image { height: auto; }
    .abx-nav { margin: 40px 0 !important; text-align: center !important; }
    .abx-nav a {
        display: inline-block !important; padding: 14px 28px !important; border-radius: 28px !important;
        background-color: #262626 !important; color: #EDEDED !important; text-decoration: none !important;
        font-family: sans-serif !important; font-size: 15px !important;
    }
""".trimIndent()

private fun pagedCss(box: PageBox) = """
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
    ${MARK_CSS}
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

/** A search hit, highlighted: light on grey, the one mark on an otherwise monochrome page. */
private const val MARK_CSS =
    "body mark.abx-hit { background-color: #5E5E5E !important; color: #FFFFFF !important; border-radius: 3px; }"

/**
 * Highlights the [occurrence]-th match of [pattern] in the chapter and says where it is: the page
 * it falls on when paging, the scroll offset that puts it a third down the screen when scrolling.
 * -1 when the chapter has no such match. Any earlier highlight is removed first.
 *
 * Counts matches text node by text node, which is how [com.absolutex.source.epub.EpubSearch]
 * counted them, so the occurrence found there is the one marked here. Should the two ever
 * disagree (a book whose markup the WebView repairs), the first match is marked rather than none.
 */
internal fun markJs(pattern: String, occurrence: Int, pageWidth: Int, scroll: Boolean): String = """
    (function() {
      document.querySelectorAll('mark.abx-hit').forEach(function(m) { m.replaceWith(document.createTextNode(m.textContent)); });
      document.body.normalize();
      var re = new RegExp(${JSONObject.quote(pattern)}, 'giu'), seen = 0, target = null, first = null, node, m;
      var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, { acceptNode: function(t) {
        return t.parentElement.closest('script,style,.abx-nav') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; } });
      while (!target && (node = walk.nextNode())) {
        re.lastIndex = 0;
        while ((m = re.exec(node.data))) {
          if (!m[0].length) { re.lastIndex++; continue; }
          var hit = [node, m.index, m[0].length];
          if (!first) first = hit;
          if (seen++ === $occurrence) { target = hit; break; }
        }
      }
      target = target || first;
      if (!target) return -1;
      var range = document.createRange();
      range.setStart(target[0], target[1]);
      range.setEnd(target[0], target[1] + target[2]);
      var mark = document.createElement('mark');
      mark.className = 'abx-hit';
      range.surroundContents(mark);
      var box = mark.getBoundingClientRect();
      return $scroll ? Math.max(0, box.top + scrollY - innerHeight / 3) : Math.floor((box.left + scrollX) / $pageWidth);
    })()
""".trimIndent()

/** A private origin: https so the WebView treats it as secure, a reserved name so it is never real. */
internal const val ORIGIN = "https://book.absolutex.invalid/"
/** Counts the columns the chapter produced, one page each, at the page width the app gave it. */
private fun measureJs(pageWidth: Int) =
    "Math.max(1, Math.ceil((Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - 2) / $pageWidth))"

/**
 * How a chapter is laid out: its text size, its page box in CSS pixels (filled in once the view
 * has a size), and whether it pages or scrolls.
 */
internal data class PageBox(
    val fontPx: Int,
    val width: Int,
    val height: Int,
    val scroll: Boolean = false,
    val links: ChapterLinks? = null,
)

/** The labels of a scrolling chapter's previous/next links, in the reader's language. */
internal data class ChapterLinks(val previous: String, val next: String)

private const val NEXT_LINK = "abx-next-chapter"
private const val PREV_LINK = "abx-previous-chapter"

/** A fixed, unscalable viewport: the layout width must not grow to fit the overflowing columns. */
private const val VIEWPORT = "width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no"
private const val LAYOUT_SETTLE_MS = 120L
private const val FULL_TEXT_ZOOM = 100
internal const val DEFAULT_FONT_PX = 18
internal const val MIN_FONT_PX = 12
internal const val MAX_FONT_PX = 32
internal const val FONT_STEP_PX = 2
private const val SIDE_PX = 22
private const val TOP_PX = 56

/** Clear of the gesture bar, which the page draws under. */
private const val BOTTOM_PX = 88
internal const val THIRDS = 3
/** How far a drag must travel, as a share of the page, to turn it on release. */
internal const val COMMIT_FRACTION = 0.2f
internal const val FLING_PX_PER_S = 800f
private const val HTTP_NOT_FOUND = 404
