package com.absolutex

import com.absolutex.feature.settings.SettingsScreen
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import android.content.ComponentCallbacks2
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.absolutex.core.ui.Motion
import javax.inject.Inject
import com.absolutex.feature.reader.BookCovers
import com.absolutex.feature.library.LocalBookCovers
import com.absolutex.feature.library.LocalCoverTransitionHost
import com.absolutex.feature.library.BookCoverSource
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.ui.AbsolutexTheme
import com.absolutex.feature.library.LibraryRoute
import com.absolutex.feature.remote.REMOTE_LIST_ROUTE
import com.absolutex.feature.remote.remoteDestination
import com.absolutex.feature.settings.SETTINGS_ROUTE
import com.absolutex.feature.settings.settingsDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.io.File
import com.absolutex.feature.reader.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val readerViewModel: ReaderViewModel by viewModels()
    private val shell: ShellViewModel by viewModels()

    /** Library cover art, provided down the tree so :feature:library never depends on the reader. */
    @Inject lateinit var covers: BookCovers

    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            readerViewModel.onTrimMemory(level)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() {
            readerViewModel.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The condition runs at first draw, after super.onCreate has injected the ViewModel.
        installSplashScreen().setKeepOnScreenCondition { shell.appPrefs.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerComponentCallbacks(trimCallback)
        // A Uri on the launch intent opens that book directly (VIEW from a file manager,
        // a device-storage location in §5.1, or an instrumented benchmark driving the reader).
        val direct = intent?.data
        // Start opening a launch Uri now rather than when the reader first composes. Composition
        // waits behind the splash (theme settings) and a first layout; on the reference phone that
        // was ~54 ms of the tap-to-first-page budget spent before the archive was even touched.
        if (direct != null) {
            readerViewModel.open(direct)
            // Every route that opens a book remembers it for §5.2 resume, not only the
            // single-document picker: a book opened by a file manager should resume too.
            shell.rememberBook(direct.toString())
        }
        setContent {
            val app = shell.appPrefs.collectAsStateWithLifecycle().value ?: return@setContent
            val dark = when (app.nightMode) {
                NightMode.ON -> true
                NightMode.OFF -> false
                NightMode.SYSTEM -> isSystemInDarkTheme()
            }
            AbsolutexTheme(darkTheme = dark, dynamicColor = app.dynamicColour, trueBlack = app.trueBlack) {
                CompositionLocalProvider(LocalBookCovers provides BookCoverSource(covers::cover)) {
                    Root(directUri = direct)
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterComponentCallbacks(trimCallback)
        super.onDestroy()
    }
}

/** Routes of the app graph. The reader carries its book's Uri, encoded, as its only argument. */
private const val LIBRARY_ROUTE = "library"
private const val READER_ROUTE = "reader/{uri}"

private fun readerRoute(uri: Uri) = "reader/${Uri.encode(uri.toString())}"

/** A library row's path: a document Uri as it stands, a device path as a file Uri. */
internal fun bookUri(path: String): Uri =
    if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))

/**
 * Remote servers list + add/edit form (§5.5). No entry point here yet: the settings row
 * navigating to the list route is Agent03's screen to add (see the TODO on
 * remoteDestination) — the route is live as soon as it does.
 */
private fun NavGraphBuilder.remoteDestinations(nav: NavHostController) {
    remoteDestination(
        onOpenForm = { id ->
            nav.navigate(if (id == null) "remote/form" else "remote/form?serverId=$id")
        },
        onFormDone = { nav.popBackStack() },
    )
}

/**
 * §5.2 auto-advance: looks up the next book off the main thread and, if there is one, replaces
 * this reader entry with it, so back from the next book returns to the library rather than to
 * the one just finished. Does nothing when there is none — auto-advance off, or nothing next.
 *
 * [scope] must belong to the reader destination itself, not an app-wide scope: leaving the reader
 * before the lookup resolves must cancel it, not navigate a screen the user already left behind.
 * [inFlight] coalesces two rapid forward attempts on the last page into one lookup and one
 * navigate, rather than firing a second of each before the first has come back.
 */
internal fun advanceFromReader(
    scope: CoroutineScope,
    vm: ShellViewModel,
    open: (Uri) -> Unit,
    bookId: String,
    inFlight: AtomicBoolean,
) {
    if (!inFlight.compareAndSet(false, true)) return
    scope.launch {
        try {
            vm.nextBook(bookId)?.let { next -> open(bookUri(next.path)) }
        } finally {
            inFlight.set(false)
        }
    }
}

/**
 * The app shell: the library is home, the reader and settings are destinations (§5.1, §5.4).
 *
 * A launch Uri makes the reader the start destination rather than a screen pushed on top of the
 * library: the library would compose, query and lay out first, and §3's tap-to-first-page budget
 * is measured from the tap. Back from that reader leaves the app, which is what a book opened
 * from a file manager should do.
 */
@Composable
private fun Root(directUri: Uri? = null, vm: ShellViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val readerVm: ReaderViewModel = hiltViewModel(context as ComponentActivity)
    // The book to resume (§5.2), once the store has been read. Navigation happens in the effect
    // below, never here: a NavController cannot navigate until its graph is set, which is what
    // composing the NavHost does — resuming from this effect crashed on every launch with a saved
    // book, and only a device showed it.
    var resume by remember { mutableStateOf<Uri?>(null) }
    // The book open over the library, if any. A string so it survives process death; the sheet
    // (BookSheet) is what shows it — the reader route is only for a book launched from outside.
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings, as a sheet over the library (and over a book, when opened from one).
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val openOverLibrary: (Uri) -> Unit = { uri ->
        openBook = uri.toString()
        nav.popBackStack(LIBRARY_ROUTE, inclusive = false)
    }
    LaunchedEffect(Unit) {
        if (directUri == null) resume = vm.resumableBook()
    }

    // A folder, not a file: a location is what the library scans, and SAF is the only way to read
    // one since Android 11 removed path access to shared storage.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) vm.addLocation(folder)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            if (keepGrant(context, picked)) vm.rememberBook(picked.toString())
            openOverLibrary(picked)
        }
    }

    AxisNavHost(nav, if (directUri != null) readerRoute(directUri) else LIBRARY_ROUTE) {
        composable(LIBRARY_ROUTE) {
            // Rescanning belongs to the screen that shows the result, not to launch: a book opened
            // from a file manager never reaches here, and §3 measures its first page from the tap.
            // Scanning during that window cost ~90 ms of cold start on the reference phone.
            LaunchedEffect(Unit) { vm.rescanLocations() }
            LibraryWithOverlays(
                openBook = openBook?.let(Uri::parse),
                settingsOpen = settingsOpen,
                onOpenBook = { uri ->
                    // Every route that opens a book remembers it for §5.2 resume (see also the
                    // single-document picker above and the launch Uri in onCreate).
                    vm.rememberBook(uri.toString())
                    openBook = uri.toString()
                },
                onCloseBook = { openBook = null },
                onSettings = { settingsOpen = it },
                onAddLocation = { folderPicker.launch(null) },
                reader = { book ->
                    ReaderDestination(book, readerVm, vm, openOverLibrary) { settingsOpen = true }
                },
                settings = {
                    SettingsScreen(
                        onOpenRemote = { nav.navigate(REMOTE_LIST_ROUTE) },
                        onAddLocation = { folderPicker.launch(null) },
                    )
                },
            )
        }
        composable(READER_ROUTE) { entry ->
            val uri = entry.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            // The activity's ReaderViewModel, not the destination's own: MainActivity.onCreate
            // starts opening a launch Uri before anything composes, and a per-destination
            // ViewModel would throw that head start away and open the book a second time.
            if (uri != null) ReaderDestination(uri, readerVm, vm, openOverLibrary) { nav.navigate(SETTINGS_ROUTE) }
        }
        // The settings row is the only way in to the remote servers list: the transports, the
        // list and the form all shipped before anything navigated to them. The route name is the
        // host's to know, which is why the callback is supplied here rather than in :feature:settings.
        // onAddLocation is the same picker the library's empty state uses. Settings is the only
        // place a SECOND folder can be added: that empty state renders only with zero locations.
        settingsDestination({ nav.navigate(REMOTE_LIST_ROUTE) }, { folderPicker.launch(null) })
        remoteDestinations(nav)
    }
    // After the NavHost: effects run in composition order, so the graph is set by the time this
    // one does. Resuming lands on top of the library, so back returns to it.
    //
    // The whole resume-and-watch sequence lives in this one coroutine, not split across effects
    // keyed on the shared reader ui.error: that state reflects whichever book is open right now,
    // and a second, unrelated book opened later (e.g. from the library) also flips error through
    // null and back. A separate effect watching (readerError, resume) would fire for that later
    // failure too, since resume was never cleared after ITS OWN book resumed successfully — and
    // then blame the wrong book's grant and bookmark for a book that in fact opened fine. Waiting
    // for this resume's own loading cycle to finish, right here, is what scopes the failure check
    // to the book resume actually opened.
    ResumeEffect(resume, readerVm, vm, onOpen = { openBook = it }, onDone = { resume = null })
}

/** Opens [resume] over the library and, if it fails to open, forgets it and closes it again. */
@Composable
private fun ResumeEffect(
    resume: Uri?,
    readerVm: ReaderViewModel,
    vm: ShellViewModel,
    onOpen: (String?) -> Unit,
    onDone: () -> Unit,
) {
    LaunchedEffect(resume) {
        val target = resume ?: return@LaunchedEffect
        onOpen(target.toString())
        readerVm.ui.first { it.loading }
        val settled = readerVm.ui.first { !it.loading }
        if (settled.error != null) {
            vm.clearLastBook(target.toString())
            onOpen(null)
        }
        onDone()
    }
}

private fun sharedAxisIn(forward: Boolean): EnterTransition =
    slideInHorizontally(Motion.enter()) { w -> (if (forward) w else -w) / Motion.SHARED_AXIS_FRACTION } +
        fadeIn(Motion.enter())

private fun sharedAxisOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(Motion.exit()) { w -> (if (forward) -w else w) / Motion.SHARED_AXIS_FRACTION } +
        fadeOut(Motion.exit())

/**
 * Takes a persistable grant on a picked document and reports whether it actually held.
 *
 * OpenDocument offers a persistable grant, but not every provider honours it — take() then throws
 * SecurityException. Attempt, then VERIFY against persistedUriPermissions: only a verified Uri
 * survives process death, and only a verified Uri is remembered for §5.2 resume.
 */
private fun keepGrant(context: android.content.Context, picked: Uri): Boolean {
    runCatching {
        context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.onFailure { android.util.Log.w("Shell", "persistable grant refused", it) }
    val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == picked }
    if (!persisted) android.util.Log.w("Shell", "grant not persisted; opening for this session only")
    return persisted
}

/**
 * The library with an open book and settings as sheets over it ([OverlaySheet]). Neither ever
 * tears the library down, so closing either has no library to rebuild. Under a sheet the library
 * holds still: it would otherwise recompose the hidden grid on every page the reader saves.
 *
 * The whole composition also sits under one [SharedTransitionLayout], which is what lets a
 * library cover grow into the reader: the card's art (source) and the reader sheet's hero
 * ([ReaderCoverHero], destination) key by the same [coverPathFor] string. [effectiveCover]
 * names the hidden card — the open book while reading, the last book through the close fade —
 * and the hero's handoff reports when the card may return, so open and close are one element
 * flown in opposite directions. The paused grid is untouched by all of it: hiding one card's
 * art flips visibility only, and the feed stays frozen until the sheet is fully gone.
 */
@Composable
private fun LibraryWithOverlays(
    openBook: Uri?,
    settingsOpen: Boolean,
    onOpenBook: (Uri) -> Unit,
    onCloseBook: () -> Unit,
    onSettings: (Boolean) -> Unit,
    onAddLocation: () -> Unit,
    reader: @Composable (Uri) -> Unit,
    settings: @Composable () -> Unit,
) {
    // Covered until a sheet has fully left, not merely been asked to: un-pausing catches the
    // library up on everything read meanwhile, and doing that inside the close animation stalled
    // it for 60 ms. After the motion, nobody sees the frame it costs.
    var bookCovers by remember { mutableStateOf(openBook != null) }
    var settingsCovers by remember { mutableStateOf(settingsOpen) }
    if (openBook != null) bookCovers = true
    if (settingsOpen) settingsCovers = true
    // The open book in library-path domain, or null once a close starts. The last book stays
    // named until the sheet is gone so its card stays hidden through the close fade; the hero's
    // handoff (handedOff) ends that cover early, in the same frame the hero leaves.
    val coverKey = openBook?.let(::coverPathFor)
    var lastCover by remember { mutableStateOf<String?>(null) }
    if (coverKey != null) lastCover = coverKey
    var handedOff by remember { mutableStateOf(false) }
    if (coverKey != null) handedOff = false
    val effectiveCover = coverKey ?: if (!handedOff && bookCovers) lastCover else null
    // The activity's ReaderViewModel, looked up by owner like Root does: the same instance the
    // reader destination opens with, so the hero reads the loading cycle it gates on.
    val context = androidx.compose.ui.platform.LocalContext.current
    val readerVm: ReaderViewModel = hiltViewModel(context as ComponentActivity)
    SharedTransitionLayout {
        val coverHost = rememberCoverHost()
        CompositionLocalProvider(LocalCoverTransitionHost provides coverHost) {
            Box(Modifier.fillMaxSize()) {
                LibraryRoute(
                    // A library row holds whatever the scan found it by: a document Uri from a SAF
                    // location, or a device path from a filesystem one. The reader opens either.
                    onOpenBook = { path -> onOpenBook(bookUri(path)) },
                    onAddLocation = onAddLocation,
                    onOpenSettings = { onSettings(true) },
                    paused = bookCovers || settingsCovers,
                    openCoverPath = effectiveCover,
                )
                OverlaySheet(
                    openBook,
                    onClose = onCloseBook,
                    onGone = {
                        bookCovers = false
                        lastCover = null
                        coverHost.landed()
                    },
                ) { book ->
                    reader(book)
                    ReaderCoverHero(
                        uri = book,
                        closing = coverKey == null,
                        handedOff = handedOff,
                        onSettledVisible = { handedOff = true },
                        vm = readerVm,
                    )
                }
                OverlaySheet(
                    if (settingsOpen) Unit else null,
                    onClose = { onSettings(false) },
                    onGone = { settingsCovers = false },
                    fromEnd = true,
                ) { settings() }
            }
        }
    }
}

/**
 * NavHost with shared-axis transitions: a screen you go into arrives from the right while the one
 * you leave recedes a little the other way, so depth reads as direction, and back reverses it.
 * Durations scale with the system animator setting, so "remove animations" turns this off.
 */
@Composable
private fun AxisNavHost(nav: NavHostController, start: String, graph: NavGraphBuilder.() -> Unit) = NavHost(
    nav,
    startDestination = start,
    // Books opened from the library are not destinations at all: they are a sheet over it
    // (BookSheet), so opening and closing one never tears down or rebuilds the library.
    enterTransition = { sharedAxisIn(forward = true) },
    exitTransition = { sharedAxisOut(forward = true) },
    popEnterTransition = { sharedAxisIn(forward = false) },
    popExitTransition = { sharedAxisOut(forward = false) },
    builder = graph,
)
