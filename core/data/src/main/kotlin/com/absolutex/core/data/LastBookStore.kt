package com.absolutex.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "absolutex_prefs")

/** Remembers the last opened book so launch can resume straight into it (§5.2). */
@Singleton
class LastBookStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val key = stringPreferencesKey("last_book_uri")

    suspend fun get(): String? = context.dataStore.data.map { it[key] }.first()

    suspend fun set(uri: String) {
        context.dataStore.edit { it[key] = uri }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(key) }
    }
}
