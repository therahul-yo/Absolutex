package com.absolutex.feature.library

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a book's cover comes from. The app supplies it; this module does not open books, and a
 * dependency on the reader module to do so would point the wrong way.
 */
fun interface BookCoverSource {
    suspend fun cover(path: String, key: String, widthPx: Int): Bitmap?
}

/** No covers until the app provides a source — previews and tests get the tone, not a crash. */
val LocalBookCovers = staticCompositionLocalOf { BookCoverSource { _, _, _ -> null } }
