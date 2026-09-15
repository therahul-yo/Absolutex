package com.absolutex.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Its own file, not LastBookStore's: two DataStores on one file in one process throw at runtime.
// A torn write resets settings to defaults rather than failing launch.
private val Context.settingsStore by preferencesDataStore(
    name = "absolutex_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The persisted settings: [PrefCodec] over Preferences DataStore.
 *
 * All defaulting and validation stays in [PrefCodec]; this class only moves a snapshot of the
 * store into a [MapPrefBag] and back. MapPrefBag already resolves a wrongly typed entry to null,
 * which is the one thing DataStore's own `Preferences[key]` gets wrong (it throws).
 *
 * ponytail: Preferences DataStore for reader prefs too, not Proto as §6 suggests. Two enums do not
 * need a schema or the protobuf plugin; switch if the reader prefs grow nested structure.
 */
@Singleton
class DataStoreSettings internal constructor(
    private val store: DataStore<Preferences>,
) : ReaderPrefsSource, AppPrefsSource, SettingsWriter {

    @Inject constructor(@ApplicationContext context: Context) : this(context.settingsStore)

    private val bags: Flow<PrefBag> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toBag() }

    override val appPrefs: Flow<AppPrefs> = bags.map(PrefCodec::decodeApp).distinctUntilChanged()

    override val readerPrefs: Flow<ReaderPrefs> = bags.map(PrefCodec::decodeReader).distinctUntilChanged()

    override suspend fun currentAppPrefs(): AppPrefs = appPrefs.first()

    override suspend fun currentReaderPrefs(): ReaderPrefs = readerPrefs.first()

    override suspend fun updateApp(transform: (AppPrefs) -> AppPrefs) {
        store.edit { prefs ->
            val bag = prefs.toBag()
            PrefCodec.encodeApp(transform(PrefCodec.decodeApp(bag)), bag)
            prefs.putAll(bag)
        }
    }

    override suspend fun updateReader(transform: (ReaderPrefs) -> ReaderPrefs) {
        store.edit { prefs ->
            val bag = prefs.toBag()
            PrefCodec.encodeReader(transform(PrefCodec.decodeReader(bag)), bag)
            prefs.putAll(bag)
        }
    }
}

private fun Preferences.toBag() = MapPrefBag(asMap().entries.associate { (key, value) -> key.name to value })

/** Keys compare by name alone, so a typed put also replaces an entry stored under another type. */
private fun MutablePreferences.putAll(bag: MapPrefBag) {
    for ((name, value) in bag.snapshot()) {
        when (value) {
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
        }
    }
}
