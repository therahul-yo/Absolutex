package com.absolutex.core.data.settings

/**
 * Library-lane keys, kept in their own file per the ownership rule so edits to [PrefCodec]
 * stay a few lines. Key names are written to disk and are frozen: add, never rename.
 */
object LibraryPrefKeys {
    /** §5.1 "use original filename": show and search the raw filename instead of the parse. */
    internal const val USE_ORIGINAL_FILENAME = "use_original_filename"
}

/**
 * Decodes and encodes this lane's keys over the shared [PrefBag].
 *
 * Every read is total like the codec's: an absent or wrongly typed key falls back to the
 * default, never throws.
 */
object LibraryPrefCodec {

    fun decodeApp(bag: PrefBag): AppPrefs {
        val defaults = AppPrefs()
        return AppPrefs(
            useOriginalFilename = bag.boolean(LibraryPrefKeys.USE_ORIGINAL_FILENAME)
                ?: defaults.useOriginalFilename,
        )
    }

    fun encodeApp(prefs: AppPrefs, bag: MutablePrefBag) {
        bag.putBoolean(LibraryPrefKeys.USE_ORIGINAL_FILENAME, prefs.useOriginalFilename)
    }
}
