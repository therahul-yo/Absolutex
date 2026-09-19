package com.absolutex.feature.remote

import android.content.Context
import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.TransportBookOpener
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
     * Network-change monitor: one callback for the app, watched per book (see
     * [RemoteBackendResolver]). Inert until the app manifest declares
     * ACCESS_NETWORK_STATE — books still open without it.
     */
    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context): RemoteNetworkMonitor =
        RemoteNetworkMonitor(context)
}
