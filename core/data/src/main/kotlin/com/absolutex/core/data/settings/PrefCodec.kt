package com.absolutex.core.data.settings

import com.absolutex.model.FitMode
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
    internal const val KEY_READING_FLOW = "reading_flow"
    internal const val KEY_FIT_MODE = "fit_mode"
    internal const val KEY_VOLUME_KEYS = "volume_keys_turn_pages"
    internal const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    internal const val KEY_ROTATION_LOCK = "rotation_lock"
    internal const val KEY_USE_CUTOUT = "use_cutout"

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
        )
    }

    fun encodeReader(prefs: ReaderPrefs, bag: MutablePrefBag) {
        bag.putString(KEY_READING_FLOW, prefs.readingFlow.name)
        bag.putString(KEY_FIT_MODE, prefs.fitMode.name)
        bag.putBoolean(KEY_VOLUME_KEYS, prefs.volumeKeysTurnPages)
        bag.putBoolean(KEY_KEEP_SCREEN_ON, prefs.keepScreenOn)
        bag.putString(KEY_ROTATION_LOCK, prefs.rotationLock.name)
        bag.putBoolean(KEY_USE_CUTOUT, prefs.useCutout)
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
}
