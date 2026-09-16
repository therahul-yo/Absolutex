package com.absolutex.core.data.settings

import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.FitMode
import com.absolutex.model.PageTransition
import com.absolutex.model.FitModeMemory
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow

/**
 * Translates between the preference models and a [PrefBag].
 *
 * Every read is total: an absent key, a wrongly typed key, an unrecognised enum name and an
 * out-of-range number all resolve to that field's default. Nothing here throws, and nothing here
 * discards a *neighbouring* field to recover from one bad one — a store half-written by an older
 * build should cost the user the settings that actually broke, not all of them.
 */
object PrefCodec {

    // Stored key names. These are written to disk, so they are frozen: renaming one silently
    // resets that setting for every existing install.
    internal const val KEY_NIGHT_MODE = "night_mode"
    internal const val KEY_DYNAMIC_COLOUR = "dynamic_colour"
    internal const val KEY_TRUE_BLACK = "true_black"
    internal const val KEY_CACHE_MIB = "cache_size_mib"
    internal const val KEY_SHOW_HIDDEN = "show_hidden_folders"
    internal const val KEY_GENERIC_ARCHIVES = "open_generic_archives"
    internal const val KEY_IMAGE_FOLDERS = "open_image_folders"
    internal const val KEY_LOCATIONS = "library_locations"
    internal const val KEY_READING_FLOW = "reading_flow"
    internal const val KEY_FIT_MODE = "fit_mode"
    internal const val KEY_VOLUME_KEYS = "volume_keys_turn_pages"
    internal const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    internal const val KEY_ROTATION_LOCK = "rotation_lock"
    internal const val KEY_USE_CUTOUT = "use_cutout"
    internal const val KEY_PAGE_LAYOUT = "page_layout"
    internal const val KEY_THUMBNAIL_STRIP = "thumbnail_strip"
    internal const val KEY_TRANSITION = "page_transition"
    internal const val KEY_PAGE_TURN_MS = "page_turn_ms"
    internal const val KEY_SCROLL_STEP = "scroll_step_percent"

    /**
     * Fits chosen per screen-and-page shape, as "SCREEN:PAGE=MODE" strings. One key holding the
     * chosen ones only: a shape never chosen in keeps following its default, so writing all four
     * up front would freeze every shape the first time any one of them is edited.
     */
    internal const val KEY_FIT_BY_CONTEXT = "fit_by_context"

    // Rendering colour keys (§5.4, Rendering group). Frozen: renaming one silently resets that
    // setting for every existing install.
    internal const val KEY_COLOUR_BRIGHTNESS = "colour_brightness"
    internal const val KEY_COLOUR_CONTRAST = "colour_contrast"
    internal const val KEY_COLOUR_SATURATION = "colour_saturation"
    internal const val KEY_COLOUR_TEMPERATURE = "colour_temperature"
    internal const val KEY_COLOUR_AGGRESSION = "colour_wb_aggression"
    internal const val KEY_COLOUR_VIBRANCE = "colour_vibrance"
    internal const val KEY_COLOUR_GAMMA = "colour_gamma"
    internal const val KEY_COLOUR_GAMMA_R = "colour_gamma_r"
    internal const val KEY_COLOUR_GAMMA_G = "colour_gamma_g"
    internal const val KEY_COLOUR_GAMMA_B = "colour_gamma_b"
    internal const val KEY_UPSCALER = "upscaler"
    internal const val KEY_CROP_ENABLED = "crop_borders"

    fun decodeApp(bag: PrefBag): AppPrefs {
        val defaults = AppPrefs()
        return AppPrefs(
            nightMode = bag.enumOr(KEY_NIGHT_MODE, defaults.nightMode, NightMode.entries),
            dynamicColour = bag.boolean(KEY_DYNAMIC_COLOUR) ?: defaults.dynamicColour,
            trueBlack = bag.boolean(KEY_TRUE_BLACK) ?: defaults.trueBlack,
            cacheSizeMiB = bag.clampedCacheSize(defaults.cacheSizeMiB),
            showHiddenFolders = bag.boolean(KEY_SHOW_HIDDEN) ?: defaults.showHiddenFolders,
            openGenericArchives = bag.boolean(KEY_GENERIC_ARCHIVES) ?: defaults.openGenericArchives,
            openImageFolders = bag.boolean(KEY_IMAGE_FOLDERS) ?: defaults.openImageFolders,
            locations = bag.stringSet(KEY_LOCATIONS) ?: defaults.locations,
        )
    }

    fun encodeApp(prefs: AppPrefs, bag: MutablePrefBag) {
        bag.putString(KEY_NIGHT_MODE, prefs.nightMode.name)
        bag.putBoolean(KEY_DYNAMIC_COLOUR, prefs.dynamicColour)
        bag.putBoolean(KEY_TRUE_BLACK, prefs.trueBlack)
        bag.putInt(KEY_CACHE_MIB, prefs.cacheSizeMiB.coerceIn(AppPrefs.MIN_CACHE_MIB, AppPrefs.MAX_CACHE_MIB))
        bag.putBoolean(KEY_SHOW_HIDDEN, prefs.showHiddenFolders)
        bag.putBoolean(KEY_GENERIC_ARCHIVES, prefs.openGenericArchives)
        bag.putBoolean(KEY_IMAGE_FOLDERS, prefs.openImageFolders)
        // Only when there are any: an empty set would write a key that says nothing.
        if (prefs.locations.isNotEmpty()) bag.putStringSet(KEY_LOCATIONS, prefs.locations)
    }

    fun decodeReader(bag: PrefBag): ReaderPrefs {
        val defaults = ReaderPrefs()
        return ReaderPrefs(
            readingFlow = bag.enumOr(KEY_READING_FLOW, defaults.readingFlow, ReadingFlow.entries),
            fitMode = bag.enumOr(KEY_FIT_MODE, defaults.fitMode, FitMode.entries),
            volumeKeysTurnPages = bag.boolean(KEY_VOLUME_KEYS) ?: defaults.volumeKeysTurnPages,
            keepScreenOn = bag.boolean(KEY_KEEP_SCREEN_ON) ?: defaults.keepScreenOn,
            rotationLock = bag.enumOr(KEY_ROTATION_LOCK, defaults.rotationLock, RotationLock.entries),
            useCutout = bag.boolean(KEY_USE_CUTOUT) ?: defaults.useCutout,
            pageLayout = bag.enumOr(KEY_PAGE_LAYOUT, defaults.pageLayout, PageLayout.entries),
            thumbnailStrip = bag.boolean(KEY_THUMBNAIL_STRIP) ?: defaults.thumbnailStrip,
            transition = bag.enumOr(KEY_TRANSITION, defaults.transition, PageTransition.entries),
            pageTurnMs = (bag.int(KEY_PAGE_TURN_MS) ?: defaults.pageTurnMs)
                .coerceIn(MIN_PAGE_TURN_MS, MAX_PAGE_TURN_MS),
            scrollStepPercent = (bag.int(KEY_SCROLL_STEP) ?: defaults.scrollStepPercent)
                .coerceIn(MIN_SCROLL_STEP_PERCENT, MAX_SCROLL_STEP_PERCENT),
            fitMemory = FitModeMemory.fromPairs(
                bag.stringSet(KEY_FIT_BY_CONTEXT).orEmpty()
                    .mapNotNull { entry ->
                        entry.split('=').takeIf { it.size == 2 }?.let { (key, value) -> key to value }
                    }
                    .toMap(),
            ),
        )
    }

    fun encodeReader(prefs: ReaderPrefs, bag: MutablePrefBag) {
        bag.putString(KEY_READING_FLOW, prefs.readingFlow.name)
        bag.putString(KEY_FIT_MODE, prefs.fitMode.name)
        bag.putBoolean(KEY_VOLUME_KEYS, prefs.volumeKeysTurnPages)
        bag.putBoolean(KEY_KEEP_SCREEN_ON, prefs.keepScreenOn)
        bag.putString(KEY_ROTATION_LOCK, prefs.rotationLock.name)
        bag.putBoolean(KEY_USE_CUTOUT, prefs.useCutout)
        bag.putString(KEY_PAGE_LAYOUT, prefs.pageLayout.name)
        bag.putBoolean(KEY_THUMBNAIL_STRIP, prefs.thumbnailStrip)
        bag.putString(KEY_TRANSITION, prefs.transition.name)
        bag.putInt(KEY_PAGE_TURN_MS, prefs.pageTurnMs.coerceIn(MIN_PAGE_TURN_MS, MAX_PAGE_TURN_MS))
        bag.putInt(KEY_SCROLL_STEP, prefs.scrollStepPercent.coerceIn(MIN_SCROLL_STEP_PERCENT, MAX_SCROLL_STEP_PERCENT))
        val fits = prefs.fitMemory.asPairs()
        if (fits.isNotEmpty()) bag.putStringSet(KEY_FIT_BY_CONTEXT, fits.map { "${it.key}=${it.value}" }.toSet())
    }

    /**
     * Rendering prefs (§5.4, Rendering group). Floats clamp to the slider ranges rather than
     * rejecting: a stored 5.0 brightness is a real intent expressed out of range, following the
     * cache-size precedent above.
     */
    fun decodeRendering(bag: PrefBag): RenderingPrefs {
        val defaults = ColourParams()
        val renderingDefaults = RenderingPrefs()
        return RenderingPrefs(
            upscaler = bag.enumOr(KEY_UPSCALER, Upscaler.PLATFORM, Upscaler.entries),
            cropEnabled = bag.boolean(KEY_CROP_ENABLED) ?: renderingDefaults.cropEnabled,
            colour = ColourParams(
                brightness = bag.gradedFloat(KEY_COLOUR_BRIGHTNESS, ColourParams.BRIGHTNESS_RANGE)
                    ?: defaults.brightness,
                contrast = bag.gradedFloat(KEY_COLOUR_CONTRAST, ColourParams.CONTRAST_RANGE)
                    ?: defaults.contrast,
                saturation = bag.gradedFloat(KEY_COLOUR_SATURATION, ColourParams.SATURATION_RANGE)
                    ?: defaults.saturation,
                temperature = bag.gradedFloat(KEY_COLOUR_TEMPERATURE, ColourParams.TEMPERATURE_RANGE)
                    ?: defaults.temperature,
                wbAggression = bag.gradedFloat(KEY_COLOUR_AGGRESSION, ColourParams.AGGRESSION_RANGE)
                    ?: defaults.wbAggression,
                vibrance = bag.gradedFloat(KEY_COLOUR_VIBRANCE, ColourParams.VIBRANCE_RANGE)
                    ?: defaults.vibrance,
                gamma = bag.gradedFloat(KEY_COLOUR_GAMMA, ColourParams.GAMMA_RANGE)
                    ?: defaults.gamma,
                gammaR = bag.gradedFloat(KEY_COLOUR_GAMMA_R, ColourParams.GAMMA_CHANNEL_RANGE)
                    ?: defaults.gammaR,
                gammaG = bag.gradedFloat(KEY_COLOUR_GAMMA_G, ColourParams.GAMMA_CHANNEL_RANGE)
                    ?: defaults.gammaG,
                gammaB = bag.gradedFloat(KEY_COLOUR_GAMMA_B, ColourParams.GAMMA_CHANNEL_RANGE)
                    ?: defaults.gammaB,
            ),
        )
    }

    fun encodeRendering(prefs: RenderingPrefs, bag: MutablePrefBag) {
        bag.putString(KEY_UPSCALER, prefs.upscaler.name)
        bag.putBoolean(KEY_CROP_ENABLED, prefs.cropEnabled)
        bag.putFloat(KEY_COLOUR_BRIGHTNESS, prefs.colour.brightness)
        bag.putFloat(KEY_COLOUR_CONTRAST, prefs.colour.contrast)
        bag.putFloat(KEY_COLOUR_SATURATION, prefs.colour.saturation)
        bag.putFloat(KEY_COLOUR_TEMPERATURE, prefs.colour.temperature)
        bag.putFloat(KEY_COLOUR_AGGRESSION, prefs.colour.wbAggression)
        bag.putFloat(KEY_COLOUR_VIBRANCE, prefs.colour.vibrance)
        bag.putFloat(KEY_COLOUR_GAMMA, prefs.colour.gamma)
        bag.putFloat(KEY_COLOUR_GAMMA_R, prefs.colour.gammaR)
        bag.putFloat(KEY_COLOUR_GAMMA_G, prefs.colour.gammaG)
        bag.putFloat(KEY_COLOUR_GAMMA_B, prefs.colour.gammaB)
    }

    /**
     * Enums are stored by name, not ordinal: an ordinal silently re-points at a different value
     * the moment someone inserts a constant in the middle of the enum.
     */
    private fun <T : Enum<T>> PrefBag.enumOr(key: String, default: T, values: List<T>): T {
        val name = string(key) ?: return default
        return values.firstOrNull { it.name == name } ?: default
    }

    private fun PrefBag.clampedCacheSize(default: Int): Int {
        val stored = int(KEY_CACHE_MIB) ?: return default
        // Clamp rather than reject: a stored 8 MiB is a real intent expressed out of range, and
        // the nearest legal value honours it better than silently restoring 512.
        return stored.coerceIn(AppPrefs.MIN_CACHE_MIB, AppPrefs.MAX_CACHE_MIB)
    }

    private fun PrefBag.gradedFloat(key: String, range: ClosedFloatingPointRange<Float>): Float? =
        float(key)?.coerceIn(range)
}
