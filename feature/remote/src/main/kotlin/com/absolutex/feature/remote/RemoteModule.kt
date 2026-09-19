package com.absolutex.feature.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.TransportBookOpener
import com.absolutex.remote.core.TransportCoverFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    fun bookOpener(resolver: RemoteBackendResolver): RemoteBookOpener =
        TransportBookOpener(resolver::transportFor)

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
}
