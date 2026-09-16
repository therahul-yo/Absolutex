package com.absolutex.feature.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.BookPrefs
import com.absolutex.core.data.BookPrefsDao
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.flow.Flow
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.core.data.settings.fitFor
import com.absolutex.model.FitContext
import com.absolutex.model.FitMode
import com.absolutex.model.PageOrientation
import com.absolutex.model.ScreenOrientation
import androidx.compose.ui.platform.LocalConfiguration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Reader options changed while reading, as opposed to in the settings screen (§5.2). */
@HiltViewModel
class ReaderOptionsViewModel @Inject constructor(
    private val writer: SettingsWriter,
    private val books: BookPrefsDao,
) : ViewModel() {

    /** Remembers the fit for this shape of screen and page only; other shapes keep theirs. */
    fun setFit(context: FitContext, mode: FitMode) {
        viewModelScope.launch { writer.updateReader { it.copy(fitMemory = it.fitMemory.with(context, mode)) } }
    }

    /** What this book overrides, live: null until it overrides anything. */
    fun bookPrefs(bookId: String): Flow<BookPrefs?> = books.observe(bookId)

    /** A manga reads right to left whatever the global default is; this is where that is said. */
    fun setFlow(bookId: String, flow: ReadingFlow) = editBook(bookId) { it.copy(readingFlow = flow.name) }

    fun setLayout(bookId: String, layout: PageLayout) = editBook(bookId) { it.copy(pageLayout = layout.name) }

    private fun editBook(bookId: String, edit: (BookPrefs) -> BookPrefs) {
        if (bookId.isEmpty()) return
        viewModelScope.launch { books.upsert(edit(books.get(bookId) ?: BookPrefs(bookId))) }
    }
}

/** The shape the reader is in now: how the device is held, crossed with the page on screen. */
@Composable
internal fun rememberFitContext(landscapePage: Boolean): FitContext {
    val screen = LocalConfiguration.current
    return FitContext(
        screen = if (screen.screenWidthDp > screen.screenHeightDp) {
            ScreenOrientation.LANDSCAPE
        } else {
            ScreenOrientation.PORTRAIT
        },
        page = if (landscapePage) PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT,
    )
}

/**
 * Per-book reading flow and page layout (§5.2's "per-book override plus global default"). Chosen
 * here, they stay with the book: a manga keeps reading right to left without the whole library
 * following it.
 */
@Composable
internal fun BookOptionsRow(
    bookId: String,
    prefs: ReaderPrefs,
    vm: ReaderOptionsViewModel = hiltViewModel(),
) {
    Row(Modifier) {
        ReadingFlow.entries.forEach { flow ->
            OptionButton(stringResource(flowLabel(flow)), flow == prefs.readingFlow) {
                vm.setFlow(bookId, flow)
            }
        }
    }
    Row(Modifier) {
        PageLayout.entries.forEach { layout ->
            OptionButton(stringResource(layoutLabel(layout)), layout == prefs.pageLayout) {
                vm.setLayout(bookId, layout)
            }
        }
    }
}

/** One option in a chrome row: the chosen one is the accent colour, the rest are quiet. */
@Composable
private fun OptionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun flowLabel(flow: ReadingFlow): Int = when (flow) {
    ReadingFlow.LTR -> R.string.reader_flow_ltr
    ReadingFlow.RTL -> R.string.reader_flow_rtl
    ReadingFlow.VERTICAL -> R.string.reader_flow_vertical
}

private fun layoutLabel(layout: PageLayout): Int = when (layout) {
    PageLayout.SINGLE -> R.string.reader_layout_single
    PageLayout.DOUBLE -> R.string.reader_layout_double
    PageLayout.DOUBLE_WITH_COVER -> R.string.reader_layout_cover
    PageLayout.CONTINUOUS_VERTICAL -> R.string.reader_layout_continuous
}

/**
 * The chrome's fit control. What it sets is remembered against the current shape of screen and
 * page (§5.2), so turning the phone, or reaching a double-page spread, brings back what was chosen
 * there rather than overwriting it.
 */
@Composable
internal fun FitRow(
    prefs: ReaderPrefs,
    context: FitContext,
    vm: ReaderOptionsViewModel = hiltViewModel(),
) {
    val current = prefs.fitFor(context)
    Row(Modifier) {
        FitMode.entries.forEach { mode ->
            OptionButton(stringResource(fitLabel(mode)), mode == current) { vm.setFit(context, mode) }
        }
    }
}

private fun fitLabel(mode: FitMode): Int = when (mode) {
    FitMode.FIT_SCREEN -> R.string.reader_fit_screen
    FitMode.FIT_WIDTH -> R.string.reader_fit_width
    FitMode.FIT_HEIGHT -> R.string.reader_fit_height
    FitMode.FULL_SIZE -> R.string.reader_fit_full
}
