package com.absolutex

import kotlinx.coroutines.launch
import com.absolutex.core.ui.rememberHaptics
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.absolutex.feature.reader.ReaderScreen
import com.absolutex.feature.reader.ReaderViewModel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reader: shown in the library's BookSheet, or as READER_ROUTE for a book launched from outside.
 *
 * A separate composable, not inlined into `Root`, so the auto-advance coroutine scope and
 * in-flight guard below are [remember]ed here: created when the reader destination composes, and
 * — critically — torn down when it is left, rather than living for the whole app session on
 * `Root`'s own scope. See [advanceFromReader].
 */
@Composable
internal fun ReaderDestination(
    uri: Uri,
    readerVm: ReaderViewModel,
    vm: ShellViewModel,
    open: (Uri) -> Unit,
    onSettings: () -> Unit,
) {
    val readerScope = rememberCoroutineScope()
    val advanceInFlight = remember { AtomicBoolean(false) }
    val haptics = rememberHaptics()
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var finished by remember(uri) { mutableStateOf<Finished?>(null) }
    Box(Modifier.fillMaxSize()) {
        ReaderScreen(
            uri = uri,
            vm = readerVm,
            onSettings = onSettings,
            onFinished = {
                // Auto-advance goes straight on; otherwise the end card offers the next book.
                advanceFromReader(readerScope, vm, open, readerVm.ui.value.bookId, advanceInFlight)
                readerScope.launch {
                    if (finished == null && !vm.autoAdvances()) {
                        haptics.confirm()
                        finished = Finished(vm.nextBook(readerVm.ui.value.bookId, onlyWhenAutoAdvancing = false))
                    }
                }
            },
        )
        EndOfBookCard(
            finished,
            onNext = { next -> open(bookUri(next.path)) },
            onLibrary = {
                finished = null
                back?.onBackPressed()
            },
            onDismiss = { finished = null },
        )
    }
}
