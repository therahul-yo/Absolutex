package com.absolutex.core.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Phase 4: a torn write (process death mid-flush) must reset the prefs, not crash launch.
// The corruptionHandler covers reads the delegate itself performs; the catch covers the flow.
private val Context.dataStore by preferencesDataStore(
    name = "absolutex_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Remembers the last opened book so launch can resume straight into it (§5.2). */
@Singleton
class LastBookStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val key = stringPreferencesKey("last_book_uri")

    suspend fun get(): String? = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[key] }.first()

    suspend fun set(uri: String) {
        context.dataStore.edit { it[key] = uri }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(key) }
    }
}
