package com.absolutex.feature.remote

import com.absolutex.remote.core.RemoteBookOpener
import com.absolutex.remote.core.TransportBookOpener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
}
