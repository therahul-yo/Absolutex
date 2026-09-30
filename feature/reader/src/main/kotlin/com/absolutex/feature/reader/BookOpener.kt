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
 *
 * [password] reaches the PDF open (see `openPdf`) and the archive open (see `openArchive`); every
 * other format ignores it. Null means no password was offered, which for an encrypted PDF or
 * archive fails with `PdfPasswordException` / `ArchivePasswordException` rather than prompting
 * anywhere down here — the prompt lives in the reader UI, which retries through this same seam.
 * It is a String because the Compose text field hands over one; the archive path copies it to a
 * CharArray it wipes, but the String itself cannot be wiped and lives until it is collected.
 * A failed open must leave nothing open: it throws instead of returning a handle.
 */
internal fun interface BookOpener {
    suspend fun open(uri: Uri, password: String?): Pair<Closeable, String>

    /** The title a reader shows. The default is the last path segment, which is the file name. */
    suspend fun titleOf(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
}

/** The production [BookOpener]: opens off Main, on [DecodeDispatchers.extract]. */
internal class ContextBookOpener(private val context: Context) : BookOpener {
    override suspend fun open(uri: Uri, password: String?): Pair<Closeable, String> =
        withContext(DecodeDispatchers.extract) {
            context.openBook(uri, password, cacheSolid = true) to context.identityOf(uri)
        }

    // A SAF document's last segment is its document id ("msf:1000092392"), not its name, so the
    // production opener asks the provider — off Main, on the same pool as the open itself.
    override suspend fun titleOf(uri: Uri): String =
        withContext(DecodeDispatchers.extract) { context.displayNameOf(uri) }
}
