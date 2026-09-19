package com.absolutex.feature.remote

import com.absolutex.core.decode.DecodeDispatchers
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.RemoteOpenResult
import com.absolutex.remote.core.TransportBookOpener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
                    (opened as? RemoteOpenResult.Ready)?.source?.let { runCatching { it.close() } }
                    throw e
                }
            }
        }
    }
}
