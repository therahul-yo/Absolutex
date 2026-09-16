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
import com.absolutex.feature.reader.ReaderScreen
import com.absolutex.feature.settings.SETTINGS_ROUTE
import com.absolutex.feature.settings.settingsDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.io.File
import com.absolutex.feature.reader.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint

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
        if (direct != null) readerViewModel.open(direct)
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
    // The book to resume (§5.2), once the store has been read. Navigation happens in the effect
    // below, never here: a NavController cannot navigate until its graph is set, which is what
    // composing the NavHost does — resuming from this effect crashed on every launch with a saved
    // book, and only a device showed it.
    var resume by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) {
        if (directUri != null) return@LaunchedEffect
        val saved = vm.lastBook() ?: return@LaunchedEffect
        val held = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == saved }
        // Pass the stale Uri so the grant (if half-held) is released, not leaked.
        if (held) resume = Uri.parse(saved) else vm.clearLastBook(saved)
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
            LibraryRoute(
                // The library stores device paths; the reader takes either a path or a SAF Uri.
                onOpenBook = { path -> nav.navigate(readerRoute(Uri.fromFile(File(path)))) },
                // TODO(library): storage locations (§5.1 milestone 3). Until they exist, this
                // picks one book rather than a folder, which is what the picker could always do.
                onAddLocation = {
                    // Generic types: the picker cannot filter by .cbz/.cbr, so widen and let
                    // libarchive decide. Filtering by extension here would hide books whose
                    // provider reports application/octet-stream.
                    picker.launch(arrayOf("*/*"))
                },
            )
        }
        composable(READER_ROUTE) { entry ->
            val uri = entry.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            if (uri != null) ReaderScreen(uri = uri, onSettings = { nav.navigate(SETTINGS_ROUTE) })
        }
        settingsDestination()
    }
    // After the NavHost: effects run in composition order, so the graph is set by the time this
    // one does. Resuming lands on top of the library, so back returns to it.
    LaunchedEffect(resume) { resume?.let { nav.navigate(readerRoute(it)) } }
}
