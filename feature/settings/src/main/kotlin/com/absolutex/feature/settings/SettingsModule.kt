package com.absolutex.feature.settings

import com.absolutex.core.data.settings.AppPrefsSource
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.data.settings.ReaderPrefsSource
import com.absolutex.core.data.settings.SettingsWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    // TODO(lane-E): replace with the DataStore-backed MutablePrefBag; InMemorySettings over a
    // bare MapPrefBag holds nothing across process death, so these bindings are session-scoped
    // behaviour until that lands.
    @Provides
    @Singleton
    fun provideSettings(): InMemorySettings = InMemorySettings()

    @Provides
    fun provideAppSource(settings: InMemorySettings): AppPrefsSource = settings

    @Provides
    fun provideReaderSource(settings: InMemorySettings): ReaderPrefsSource = settings

    @Provides
    fun provideWriter(settings: InMemorySettings): SettingsWriter = settings
}
