package com.absolutex.core.data.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** One persisted settings instance behind all three contracts, so a write is seen by every reader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {

    @Binds
    abstract fun readerPrefs(settings: DataStoreSettings): ReaderPrefsSource

    @Binds
    abstract fun appPrefs(settings: DataStoreSettings): AppPrefsSource

    @Binds
    abstract fun writer(settings: DataStoreSettings): SettingsWriter
}
