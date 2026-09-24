package com.absolutex

import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.absolutex.core.ui.Motion
import com.absolutex.feature.library.CoverTransitionHost
import com.absolutex.feature.library.coverTransitionKey

/**
 * The library path for a book open as [uri]. Library rows are keyed by path — a device path, or
 * a document Uri string — while the sheet carries a [Uri]; this folds the Uri back to the
 * row's domain so the shared element's two ends key identically. A `file:` Uri round-trips
 * through [Uri.getPath] to the path the row was opened from, and a `content:` Uri was the
 * row's path verbatim. Anything else (a remote book opened over the library) matches no card,
 * and the transition degrades to the sheet's own motion rather than flying from nowhere.
 */
fun coverPathFor(uri: Uri): String =
    if (uri.scheme == "content") uri.toString() else uri.path ?: uri.toString()

/**
 * The app's [CoverTransitionHost]: both ends of the cover flight are composed under one
 * [SharedTransitionLayout] (see `LibraryWithOverlays`), and this captures its scope so the
 * library never touches the transition APIs itself.
 *
 * The flight reuses [Motion.enter]: 200 ms on the emphasized curve — inside the 300 ms budget
 * and the same easing as every other entering thing, with no spring and nothing to wobble.
 * Compose scales the duration with the system animator setting, so the existing remove-animations
 * path holds with no branch here.
 */
private class SharedCoverHost(private val scope: SharedTransitionScope) : CoverTransitionHost {
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    override fun coverModifier(path: String, visible: Boolean): Modifier {
        val state = scope.rememberSharedContentState(coverTransitionKey(path))
        return with(scope) {
            Modifier.sharedElementWithCallerManagedVisibility(
                state,
                visible,
                boundsTransform = { _, _ -> Motion.enter() },
            )
        }
    }
}

/**
 * Remembers the [CoverTransitionHost] for this [SharedTransitionScope]. Must be called inside
 * the `SharedTransitionLayout` content, whose receiver supplies the scope both ends share.
 */
@Composable
fun SharedTransitionScope.rememberCoverHost(): CoverTransitionHost = remember(this) { SharedCoverHost(this) }
