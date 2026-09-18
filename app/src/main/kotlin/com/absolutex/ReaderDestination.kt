package com.absolutex

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import com.absolutex.feature.reader.ReaderScreen
import com.absolutex.feature.reader.ReaderViewModel
import com.absolutex.feature.settings.SETTINGS_ROUTE
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reader destination (READER_ROUTE in [MainActivity]'s NavHost).
 *
 * A separate composable, not inlined into `Root`, so the auto-advance coroutine scope and
 * in-flight guard below are [remember]ed here: created when the reader destination composes, and
 * — critically — torn down when it is left, rather than living for the whole app session on
 * `Root`'s own scope. See [advanceFromReader].
 */
@Composable
internal fun ReaderDestination(uri: Uri, readerVm: ReaderViewModel, vm: ShellViewModel, nav: NavHostController) {
    val readerScope = rememberCoroutineScope()
    val advanceInFlight = remember { AtomicBoolean(false) }
    ReaderScreen(
        uri = uri,
        vm = readerVm,
        onSettings = { nav.navigate(SETTINGS_ROUTE) },
        onFinished = { advanceFromReader(readerScope, vm, nav, readerVm.ui.value.bookId, advanceInFlight) },
    )
}
