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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.absolutex.core.ui.AbsolutexTheme
import com.absolutex.feature.reader.ReaderScreen
import com.absolutex.feature.reader.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val readerViewModel: ReaderViewModel by viewModels()

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerComponentCallbacks(trimCallback)
        // A Uri on the launch intent opens that book directly (VIEW from a file manager,
        // a device-storage location in §5.1, or an instrumented benchmark driving the reader).
        val direct = intent?.data
        setContent { AbsolutexTheme { Root(directUri = direct) } }
    }

    override fun onDestroy() {
        unregisterComponentCallbacks(trimCallback)
        super.onDestroy()
    }
}

/**
 * Phase 2 entry point: SAF picker straight into the reader.
 * TODO(phase4): replaced by the library home (Locations, Series, Folders, Unread...).
 */
@Composable
private fun Root(directUri: Uri? = null, vm: ShellViewModel = hiltViewModel()) {
    var uri by remember { mutableStateOf(directUri) }
    var restored by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Resume the last book on launch (§5.2). The persisted grant is what makes this survive
    // process death; without takePersistableUriPermission the saved Uri would be dead on relaunch.
    LaunchedEffect(Unit) {
        if (directUri != null) { restored = true; return@LaunchedEffect }
        val saved = vm.lastBook()
        if (saved != null) {
            val held = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == saved }
            // Pass the stale Uri so the grant (if half-held) is released, not leaked.
            if (held) uri = Uri.parse(saved) else vm.clearLastBook(saved)
        }
        restored = true
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { picked ->
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
            uri = picked
        }
    }

    if (!restored) return

    val current = uri
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                // Generic types: the picker cannot filter by .cbz/.cbr, so widen and let
                // libarchive decide. Filtering by extension here would hide books whose
                // provider reports application/octet-stream.
                picker.launch(arrayOf("*/*"))
            }) { Text(stringResource(R.string.open_book)) }
        }
    } else {
        ReaderScreen(uri = current)
    }
}
