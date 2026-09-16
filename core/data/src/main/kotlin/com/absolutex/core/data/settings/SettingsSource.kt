package com.absolutex.core.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the reader reads its preferences.
 *
 * This is the whole contract the reader needs, and it is read-only on purpose: the reader renders
 * according to these, it does not own them, so it cannot write one by accident. Writes go through
 * [SettingsWriter] from the settings screen.
 */
interface ReaderPrefsSource {
    val readerPrefs: Flow<ReaderPrefs>

    /** Current value without collecting, for a first frame that must not wait on a flow. */
    suspend fun currentReaderPrefs(): ReaderPrefs
}

/** How the theme and the library read app-wide flags. Read-only for the same reason. */
interface AppPrefsSource {
    val appPrefs: Flow<AppPrefs>

    suspend fun currentAppPrefs(): AppPrefs
}

/** The settings screen's write access. */
interface SettingsWriter {
    suspend fun updateApp(transform: (AppPrefs) -> AppPrefs)
    suspend fun updateReader(transform: (ReaderPrefs) -> ReaderPrefs)
}

/**
 * Preferences held in memory, persisted through whatever [MutablePrefBag] it is given.
 *
 * With a [MapPrefBag] this is the test double and the implementation the screens can run against
 * until the DataStore adapter exists. Backing it with a DataStore-fronted bag makes it the real
 * thing, which is the point of the seam.
 *
 * The persisted implementation is [DataStoreSettings]: the same [PrefCodec] validation over
 * Preferences DataStore, bound to all three contracts in SettingsDataModule. This class stays as
 * the test double behind [MapPrefBag]; every public signature here is frozen for existing consumers.
 */
class InMemorySettings(
    private val bag: MutablePrefBag = MapPrefBag(),
) : ReaderPrefsSource, AppPrefsSource, SettingsWriter {

    private val appState = MutableStateFlow(PrefCodec.decodeApp(bag))
    private val readerState = MutableStateFlow(PrefCodec.decodeReader(bag))

    override val appPrefs: StateFlow<AppPrefs> = appState.asStateFlow()
    override val readerPrefs: StateFlow<ReaderPrefs> = readerState.asStateFlow()

    override suspend fun currentAppPrefs(): AppPrefs = appState.value

    override suspend fun currentReaderPrefs(): ReaderPrefs = readerState.value

    override suspend fun updateApp(transform: (AppPrefs) -> AppPrefs) {
        val updated = transform(appState.value)
        PrefCodec.encodeApp(updated, bag)
        // Re-decode rather than publishing `updated` directly, so a value the codec clamps is
        // reported back as what was actually stored instead of what was asked for.
        appState.value = PrefCodec.decodeApp(bag)
    }

    override suspend fun updateReader(transform: (ReaderPrefs) -> ReaderPrefs) {
        val updated = transform(readerState.value)
        PrefCodec.encodeReader(updated, bag)
        readerState.value = PrefCodec.decodeReader(bag)
    }
}
