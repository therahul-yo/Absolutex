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
import com.absolutex.core.data.settings.ReaderPrefs
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
class ReaderOptionsViewModel @Inject constructor(private val writer: SettingsWriter) : ViewModel() {

    /** Remembers the fit for this shape of screen and page only; other shapes keep theirs. */
    fun setFit(context: FitContext, mode: FitMode) {
        viewModelScope.launch { writer.updateReader { it.copy(fitMemory = it.fitMemory.with(context, mode)) } }
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
            TextButton(onClick = { vm.setFit(context, mode) }) {
                Text(
                    text = stringResource(fitLabel(mode)),
                    color = if (mode == current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun fitLabel(mode: FitMode): Int = when (mode) {
    FitMode.FIT_SCREEN -> R.string.reader_fit_screen
    FitMode.FIT_WIDTH -> R.string.reader_fit_width
    FitMode.FIT_HEIGHT -> R.string.reader_fit_height
    FitMode.FULL_SIZE -> R.string.reader_fit_full
}
