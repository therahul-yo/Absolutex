package com.absolutex.remote.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private val Context.syncServersFile by preferencesDataStore(name = "sync_servers")
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
    fun syncServers(@ApplicationContext context: Context): SyncServers =
        SyncServers(context.syncServersFile)

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
}
