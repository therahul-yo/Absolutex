package com.absolutex

import android.content.ComponentCallbacks2
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
                Root(directUri = direct)
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
private fun bookUri(path: String): Uri =
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
    nav: NavHostController,
    bookId: String,
    inFlight: AtomicBoolean,
) {
    if (!inFlight.compareAndSet(false, true)) return
    scope.launch {
        try {
            vm.nextBook(bookId)?.let { next ->
                nav.navigate(readerRoute(bookUri(next.path))) { popUpTo(READER_ROUTE) { inclusive = true } }
            }
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
            // OpenDocument offers a persistable grant, but not every provider honours
            // it — take() then throws SecurityException. Attempt, then VERIFY against
            // persistedUriPermissions: only a verified Uri survives process death, and
            // only a verified Uri is remembered for §5.2 resume.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { android.util.Log.w("Shell", "persistable grant refused", it) }
            val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == picked }
            if (!persisted) {
                android.util.Log.w("Shell", "grant not persisted; opening for this session only")
            } else {
                vm.rememberBook(picked.toString())
            }
            nav.navigate(readerRoute(picked))
        }
    }

    NavHost(nav, startDestination = if (directUri != null) readerRoute(directUri) else LIBRARY_ROUTE) {
        composable(LIBRARY_ROUTE) {
            // Rescanning belongs to the screen that shows the result, not to launch: a book opened
            // from a file manager never reaches here, and §3 measures its first page from the tap.
            // Scanning during that window cost ~90 ms of cold start on the reference phone.
            LaunchedEffect(Unit) { vm.rescanLocations() }
            LibraryRoute(
                // A library row holds whatever the scan found it by: a document Uri from a SAF
                // location, or a device path from a filesystem one. The reader opens either.
                onOpenBook = { path ->
                    // Every route that opens a book remembers it for §5.2 resume (see also the
                    // single-document picker below and the launch Uri in onCreate) — the library
                    // is the app's main, everyday route and used to be the one route that didn't.
                    vm.rememberBook(bookUri(path).toString())
                    nav.navigate(readerRoute(bookUri(path)))
                },
                onAddLocation = { folderPicker.launch(null) },
            )
        }
        composable(READER_ROUTE) { entry ->
            val uri = entry.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            // The activity's ReaderViewModel, not the destination's own: MainActivity.onCreate
            // starts opening a launch Uri before anything composes, and a per-destination
            // ViewModel would throw that head start away and open the book a second time.
            if (uri != null) ReaderDestination(uri, readerVm, vm, nav)
        }
        // The settings row is the only way in to the remote servers list: the transports, the
        // list and the form all shipped before anything navigated to them. The route name is the
        // host's to know, which is why the callback is supplied here rather than in :feature:settings.
        settingsDestination(onOpenRemote = { nav.navigate(REMOTE_LIST_ROUTE) })
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
    LaunchedEffect(resume) {
        val target = resume ?: return@LaunchedEffect
        nav.navigate(readerRoute(target))
        readerVm.ui.first { it.loading }
        val settled = readerVm.ui.first { !it.loading }
        if (settled.error != null) {
            vm.clearLastBook(target.toString())
            nav.popBackStack(LIBRARY_ROUTE, inclusive = false)
        }
        resume = null
    }
}
