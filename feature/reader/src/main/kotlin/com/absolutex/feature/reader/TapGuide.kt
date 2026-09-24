package com.absolutex.feature.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.absolutex.core.ui.Motion
import kotlinx.coroutines.delay

/**
 * The first-open guide: once ever, the page shows which side turns forward, which goes back, and
 * that the middle opens the controls — mirrored for a right-to-left book. It fades after a few
 * seconds or at the first touch, which it swallows so that touch does not also turn the page.
 */
@Composable
internal fun TapGuide(rightToLeft: Boolean) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(!context.hintSeen()) }
    LaunchedEffect(Unit) {
        if (!shown) return@LaunchedEffect
        context.markHintSeen()
        delay(GUIDE_MS)
        shown = false
    }
    AnimatedVisibility(shown, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM))
                .pointerInput(Unit) { detectTapGestures { shown = false } },
        ) {
            val back = Zone(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.reader_guide_back))
            val next = Zone(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.reader_guide_next))
            val menu = Zone(Icons.Outlined.Menu, stringResource(R.string.reader_guide_menu))
            listOf(if (rightToLeft) next else back, menu, if (rightToLeft) back else next).forEach { zone ->
                GuideZone(zone, Modifier.weight(1f))
            }
        }
    }
}

private class Zone(val icon: ImageVector, val label: String)

@Composable
private fun GuideZone(zone: Zone, modifier: Modifier) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(zone.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(zone.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun Context.hintSeen() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false)

private fun Context.markHintSeen() =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply()

private const val PREFS = "reader_hints"
private const val SEEN = "tap_guide_seen"
private const val GUIDE_MS = 3500L
private const val SCRIM = 0.55f
