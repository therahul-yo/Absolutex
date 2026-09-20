package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.absolutex.remote.core.HttpCall
import com.absolutex.remote.core.HttpUrlConnectionCall
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private val Context.syncQueueFile by preferencesDataStore(name = "sync_queue")

/**
 * Sync wiring for the app graph. Everything here is Singleton-scoped and inert until the
 * trigger call sites fire (see SyncController's TODOs): merely depending on `:remote:sync`
 * starts no work and opens no connections.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun remoteServers(@ApplicationContext context: Context): RemoteServers =
        RemoteServers(context)

    @Provides
    @Singleton
    fun serverAdmin(servers: RemoteServers, secrets: SyncSecrets): ServerAdmin =
        ServerAdmin(servers, secrets)

    @Provides
    @Singleton
    fun syncSecrets(@ApplicationContext context: Context): SyncSecrets =
        SyncSecrets(AndroidKeyStoreCredentialStore(File(context.filesDir, "sync-creds")))

    @Provides
    @Singleton
    fun syncQueue(@ApplicationContext context: Context): SyncQueue =
        SyncQueue(context.syncQueueFile)

    @Provides
    @Singleton
    fun httpCall(): HttpCall = HttpUrlConnectionCall()

    @Provides
    @Singleton
    fun serverClock(): ServerClock = ServerClock()

    @Provides
    @Singleton
    fun komgaProbe(http: HttpCall): KomgaConnectionProbe = KomgaConnectionProbe(http)

    @Provides
    @Singleton
    fun kavitaProbe(http: HttpCall): KavitaConnectionProbe = KavitaConnectionProbe(http)
}
