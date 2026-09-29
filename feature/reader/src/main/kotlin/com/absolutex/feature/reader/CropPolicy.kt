package com.absolutex.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.gpu.CropRect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether border crop applies to a page: the user's choice, except for a PDF.
 *
 * Crop trims a scan's empty margins. A document's margins are designed, and its thin marginal
 * labels read as blank on the crop thumbnail, so cropping cut them: a PDF stays uncropped whatever
 * the setting says. Kept as a plain function so the policy is stated once and tested without a
 * composition.
 */
internal fun cropApplies(preference: Boolean, isPdf: Boolean): Boolean = preference && !isPdf

/**
 * The live crop policy for the page being drawn. Seeded from the preference's current value, not
 * a default, so a user who turned crop off does not see one cropped first frame; recomposes only
 * on a real switch (distinctUntilChanged keeps a colour edit from touching it).
 */
@Composable
internal fun rememberCropEnabled(vm: ReaderViewModel): Boolean {
    val flow = remember(vm) { vm.renderingPrefs.map { it.cropEnabled }.distinctUntilChanged() }
    val seed = remember(vm) { vm.renderingPrefs.value.cropEnabled }
    val preference by flow.collectAsStateWithLifecycle(initialValue = seed)
    val isPdf by vm.isPdf.collectAsStateWithLifecycle()
    return cropApplies(preference, isPdf)
}

/**
 * The aspect a continuous-strip page is laid out at once its crop is decided: the crop's own
 * aspect, or the whole page's when there is no crop ([crop] null: none found, or crop switched
 * off). Null when neither is known yet, and the caller keeps what it had.
 *
 * Without the fallback, switching crop off left every page the strip had already cropped laid out
 * at its cropped aspect, so the uncropped page drew squeezed into a box of the wrong shape.
 */
internal fun stripAspectFor(crop: CropRect?, fullAspect: Float?): Float? =
    crop?.let { it.width.toFloat() / it.height } ?: fullAspect

/** Per-page aspect ratios a strip lays its pages out at, and the whole-page ones to fall back to. */
internal class StripAspects {
    private val shown = mutableStateMapOf<Int, Float>()
    private val full = HashMap<Int, Float>()

    operator fun get(index: Int): Float? = shown[index]

    /** The page's header is read: it is laid out uncropped until a crop lands. */
    fun loaded(index: Int, width: Int, height: Int) {
        val aspect = width.toFloat() / height
        full[index] = aspect
        shown[index] = aspect
    }

    /** The page's crop is decided, or absent. */
    fun cropped(index: Int, crop: CropRect?) {
        stripAspectFor(crop, full[index])?.let { shown[index] = it }
    }
}
