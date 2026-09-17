package com.absolutex.feature.reader

import android.content.Context
import android.net.Uri
import com.absolutex.core.decode.DecodeDispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Seam over [Context.openBook]/[Context.identityOf] (see OpenBook.kt), so `ReaderViewModel`'s
 * open/cancel races (dropping a reopen of the current book while a different one is in flight;
 * leaking the just-opened handle on a cancel that lands mid-open) are a plain unit test against a
 * fake source, not something only a real archive or PDF can exercise.
 */
internal fun interface BookOpener {
    suspend fun open(uri: Uri): Pair<Closeable, String>
}

/** The production [BookOpener]: opens off Main, on [DecodeDispatchers.extract]. */
internal class ContextBookOpener(private val context: Context) : BookOpener {
    override suspend fun open(uri: Uri): Pair<Closeable, String> = withContext(DecodeDispatchers.extract) {
        context.openBook(uri) to context.identityOf(uri)
    }
}
