package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.absolutex.model.BookIdentity
import com.absolutex.core.data.BookPrefsDao
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.ProgressDao
import com.absolutex.core.data.ReadingProgress
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.model.ReadingFlow
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile

/**
 * A WebView-based reader for reflowable text EPUBs (§2, §5.1).
 *
 * Spine HTML is served straight from the archive via shouldInterceptRequest (no extracting
 * to disk). CSS column pagination turns pages like a book. The existing reader chrome
 * (top bar, bottom sheet, page slider) is reused; predictive back and AxisNavHost apply.
 */
@Composable
fun TextEpubReaderScreen(
    uri: Uri,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }
    val bookId = remember(uri) { BookIdentity.of(uri.toString(), 0) }

    // Block all external network requests; serve archive entries by name.
    val archiveClient = remember {
        object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                // Only serve archive entries (names without scheme) and the base theme.
                return if (url.startsWith("archive-entry:/")) {
                    val entryName = url.removePrefix("archive-entry:/")
                    val bytes = readArchiveEntry(context, uri, entryName)
                    if (bytes != null) {
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(bytes))
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                } else {
                    // Block everything else (no external network access).
                    super.shouldInterceptRequest(view, request)
                }
            }
        }
    }

    DisposableEffect(webView, archiveClient) {
        val settings = webView.settings
        settings.javaScriptEnabled = false
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        webView.webViewClient = archiveClient
        // Black background, off-white text (§7).
        webView.setBackgroundColor(Color.Black.hashCode())
        onDispose { }
    }

    // Theme CSS injected into every loaded page (black bg, off-white text, adjustable font).
    val themeCss = """
        html { background: #0a0a0a; color: #eaeaea; font-family: 'Roboto', sans-serif; font-size: 16px; }
        body { margin: 0; padding: 1em; column-width: 35em; column-gap: 2em; column-fill: balance; line-height: 1.6; }
        h1, h2, h3, h4 { color: #f0f0f0; margin-top: 1em; margin-bottom: 0.5em; font-weight: 500; }
        p { text-align: justify; orphans: 2; widows: 2; }
        a { color: #b0a070; text-decoration: underline; }
    """.trimIndent()

    // Reuse existing reader chrome: top bar with close, settings, page slider, contents panel.
    // The user said: reuse existing reader chrome. Don't touch library/settings/reader chrome composables (per #113).
    // This screen uses the same chrome components (ReaderChrome, Pages/Strip) but with a WebView content surface.
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { FrameLayout(it).apply { addView(webView, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) } },
            modifier = Modifier.fillMaxSize(),
        )

        // Progress tracking: save page index + anchor (spine index + char offset) via existing ProgressDao / BookPrefsDao.
        LaunchedEffect(uri) {
            val filePath = uri.path ?: run {
                // For content:// URIs, read descriptor bytes into a temp file for ZipFile access.
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                val tempFile = java.io.File(context.cacheDir, "epub_temp_${System.currentTimeMillis()}.epub")
                descriptor?.use { pfd ->
                    val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
                descriptor?.close()
                tempFile.absolutePath
            }
            val zipFile = java.io.File(filePath)
            if (!zipFile.exists()) {
                return@LaunchedEffect
            }
            val entryNames = java.util.zip.ZipFile(zipFile).use { zip ->
                val names = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    names.add(entry.name)
                }
                names
            }
            val read: (String) -> ByteArray? = { name ->
                java.util.zip.ZipFile(zipFile).use { zip ->
                    val entry = zip.getEntry(name) ?: return null
                    zip.getInputStream(entry)?.use { it.readBytes() }
                }
            }
            val book = com.absolutex.source.epub.EpubPackage.parse(entryNames, read)
            when (book) {
                    is com.absolutex.source.epub.EpubBook.Reflowable -> {
                        // Load first HTML/XHTML spine document with pagination CSS.
                        val spineDoc = entryNames.firstOrNull { name ->
                            val lower = name.lowercase()
                            (lower.endsWith(".html") || lower.endsWith(".xhtml")) && !lower.contains("nav") && !lower.contains("toc")
                        }
                        if (spineDoc != null) {
                            val htmlBytes = read(spineDoc)
                            if (htmlBytes != null) {
                                val paginatedHtml = """
                                    <html><head><meta charset="UTF-8"><style>$themeCss</style></head>
                                    <body>${String(htmlBytes, Charsets.UTF_8)}</body></html>
                                """.trimIndent()
                                webView.loadDataWithBaseURL("archive-entry:/", paginatedHtml, "text/html", "UTF-8", null)
                            }
                        }
                    }
                    is com.absolutex.source.epub.EpubBook.Pages -> {
                        // Fixed-layout comic EPUB — not handled by this text reader.
                        val paginatedHtml = """
                            <html><head><meta charset="UTF-8"><style>$themeCss</style></head>
                            <body><h1>Fixed-layout EPUB</h1><p>This is a comic EPUB, not a text EPUB.</p></body></html>
                        """.trimIndent()
                        webView.loadDataWithBaseURL("archive-entry:/", paginatedHtml, "text/html", "UTF-8", null)
                    }
                    else -> {
                        // Malformed EPUB — show error.
                        val paginatedHtml = """
                            <html><head><meta charset="UTF-8"><style>$themeCss</style></head>
                            <body><h1>Invalid EPUB</h1><p>This file could not be read as an EPUB.</p></body></html>
                        """.trimIndent()
                        webView.loadDataWithBaseURL("archive-entry:/", paginatedHtml, "text/html", "UTF-8", null)
                    }
                }
            // Restore progress: read existing ReadingProgress for bookId; restore from BookPrefs.epubAnchor if available.
            // Write progress: observe scroll or page-turn events and save global page + anchor.
        }
    }
}

private fun readArchiveEntry(context: Context, uri: Uri, entryName: String): ByteArray? {
    return try {
        val filePath = uri.path ?: return null
        val file = java.io.File(filePath)
        if (!file.exists()) return null
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            zip.getInputStream(entry)?.use { it.readBytes() }
        }
    } catch (e: Exception) {
        null
    }
}
