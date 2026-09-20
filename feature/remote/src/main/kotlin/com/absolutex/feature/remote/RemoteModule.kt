package com.absolutex.feature.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.remote.core.TransportBookOpener
import com.absolutex.remote.core.TransportCoverFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Remote reading graph. [RemoteBookOpener] resolves `absolutex-remote://` Uris through
 * [RemoteBackendResolver] (SMB/FTP transports, Keystore secrets) into the shared
 * streaming stack. Singleton-scoped and inert: merely depending on `:feature:remote`
 * opens no connections; the first open dials.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    /**
     * Folder listing behind the [RemoteBrowser] seam: the browse ViewModel lists through
     * the interface, the resolver owns the backends.
     */
    @Provides
    @Singleton
    fun remoteBrowser(resolver: RemoteListingResolver): RemoteBrowser = resolver

    /**
     * Grid covers through the shared fetcher, wired to the same backend function as
     * opens — one seam for both flows, so covers never need a hand-built Uri.
     */
    @Provides
    @Singleton
    fun coverFetcher(resolver: RemoteBackendResolver): TransportCoverFetcher =
        TransportCoverFetcher(resolver::transportFor)

    /** Last-folder-per-server store for the browser's cross-launch memory. */
    @Provides
    @Singleton
    fun browseHistoryFile(@ApplicationContext context: Context): DataStore<Preferences> =
        context.browseHistoryFile

    @Provides
    @Singleton
    fun bookOpener(resolver: RemoteBackendResolver): RemoteBookOpener {
        val transport = TransportBookOpener(resolver::transportFor)
        // TransportBookOpener lives in :remote:core (plain JVM, no Android/Hilt — see its own
        // KDoc), so it cannot see DecodeDispatchers itself. This is the seam where Hilt exists:
        // SMBJ and Commons Net are blocking by design (SmbjTransport's KDoc), so the real opener
        // must get off Main here, same pool local archive extraction already blocks on.
        //
        // RemoteBookOpener.open is a real suspend fun and the reader calls it plainly — no
        // NonCancellable dance the way it needs one for the blocking local BookOpener — but the
        // dispatcher switch above is itself a withContext, and withContext has the same hazard
        // NonCancellable exists for elsewhere in this codebase: if the reader's job is cancelled
        // while [transport.open] is already running, the switch can finish opening the book and
        // then still resume the caller with a CancellationException instead of the result,
        // discarding the just-opened [RemoteOpenResult] — and the transport it owns — before
        // anyone ever sees it to close it. `opened` is captured from inside the block, so it
        // survives that discard: the catch below is what closes it. This is the opener's own
        // job, per its KDoc ("the reader calls it ... same as every other open path") — the
        // reader only has to trust the suspend contract, not defeat cancellation for it.
        return object : RemoteBookOpener {
            override suspend fun open(uri: String): RemoteOpenResult {
                var opened: RemoteOpenResult? = null
                return try {
                    withContext(DecodeDispatchers.extract) { transport.open(uri).also { opened = it } }
                } catch (e: CancellationException) {
                    // NonCancellable + the extract pool, not inline: this catch runs after
                    // withContext has resumed the caller on ITS dispatcher, which is Main, and
                    // closing a transport is a session teardown round trip (SMB logoff, FTP QUIT
                    // bounded only by a 30 s socket timeout). Closing here directly would block a
                    // frame — or the whole UI, against a NAS that has gone away.
                    (opened as? RemoteOpenResult.Ready)?.source?.let { handle ->
                        withContext(NonCancellable + DecodeDispatchers.extract) {
                            runCatching { handle.close() }
                        }
                    }
                    throw e
                }
            }
        }
    }
}
