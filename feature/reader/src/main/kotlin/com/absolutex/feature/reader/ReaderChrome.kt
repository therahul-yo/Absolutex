package com.absolutex.feature.reader

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.absolutex.core.ui.Motion
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import com.absolutex.core.data.settings.ReaderPrefs
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.absolutex.model.TocEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import com.absolutex.model.FitContext
import com.absolutex.core.data.settings.fitFor
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Page indicator and seek bar, shown only with the chrome. Seeking is the only way to cross a
 * 45-page book in one move; tapping and swiping are both one page at a time.
 */
@Composable
internal fun ReaderChrome(
    visible: Boolean,
    page: Int,
    pageCount: Int,
    title: String,
    onSettings: (() -> Unit)?,
    onSeek: (Int) -> Unit,
    onDismiss: () -> Unit,
    bookId: String,
    strip: (suspend (index: Int, width: Int) -> Bitmap?)?,
    toc: List<TocEntry>,
    onExport: suspend () -> Uri?,
    /** Null in a layout whose fit is fixed, such as the continuous strip. */
    fitFor: FitContext?,
    prefs: ReaderPrefs,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 0) return
    Box(modifier.fillMaxSize()) {
        // Bars slide in from their own edges and fade: quick, and never a pop.
        AnimatedVisibility(
            visible,
            enter = slideInVertically(Motion.enter()) { -it } + fadeIn(Motion.enter()),
            exit = slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) { ReaderTopBar(title, onSettings) }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        BottomChrome(page, pageCount, onSeek, onDismiss, bookId, strip, toc, onExport, fitFor, prefs)
    }
    }
}

/** The bottom sheet of the chrome: actions, strip, bookmarks, seek bar and, on demand, options. */
@Composable
private fun BottomChrome(
    page: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
    onDismiss: () -> Unit,
    bookId: String,
    strip: (suspend (index: Int, width: Int) -> Bitmap?)?,
    toc: List<TocEntry>,
    onExport: suspend () -> Uri?,
    fitFor: FitContext?,
    prefs: ReaderPrefs,
) {
    // While dragging, the thumb and label follow the finger locally; the pager moves once, on
    // release. Seeking through the pager on every drag tick launched an animated scroll per tick,
    // each cancelling the last, and the page stuttered behind the thumb.
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Saveable, like chromeState above: a config change this activity does not declare (font
    // scale, locale, keyboard) must not close a TOC or options panel the reader has open.
    var contents by rememberSaveable { mutableStateOf(false) }
    // Landscape has ~1200 px of height and the chrome had grown past it, so the options moved
    // behind a toggle: what is always shown is what a reader looks at every page.
    var options by rememberSaveable { mutableStateOf(false) }
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
        if (options) withFrameNanos { }.also { chromeScroll.animateScrollTo(chromeScroll.maxValue) }
    }
    val pull = remember { Animatable(0f) } // the sheet's drag offset; see [DragHandle]
    Surface(
        // Opaque, with the content colour stated: a colour with its alpha changed is no scheme
        // colour, so contentColorFor fell back to black and plain icons (settings, bookmark)
        // drew black on the dark bar. And a see-through bar let page text bleed into the controls.
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxChrome)
            .graphicsLayer { translationY = pull.value },
    ) {
        Column(
            Modifier.verticalScroll(chromeScroll).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            DragHandle(pull, onDismiss)
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

/**
 * The sheet's grab handle: a short pill with a tall, full-width touch area. Dragging it moves the
 * sheet with the finger ([pull] is the sheet's offset); past a threshold or on a downward fling it
 * closes, and let go early it springs back. Only the handle drags, so the strip, the seek bar and
 * the options keep their own gestures.
 */
@Composable
private fun DragHandle(pull: Animatable<Float, *>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { DISMISS_DISTANCE.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(HANDLE_TOUCH_HEIGHT)
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch { pull.snapTo((pull.value + delta).coerceAtLeast(0f)) }
                },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val close = pull.value > closeAt || velocity > DISMISS_VELOCITY
                    if (close) onDismiss() else pull.animateTo(0f, Motion.press())
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA), CircleShape),
        )
    }
}

private val DISMISS_DISTANCE = 72.dp
private val HANDLE_TOUCH_HEIGHT = 28.dp
private const val DISMISS_VELOCITY = 1200f
private const val HANDLE_ALPHA = 0.5f
