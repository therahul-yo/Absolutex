package com.absolutex.core.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.absolutex.core.data.backup.BackupPreferences
import com.absolutex.core.data.backup.MAX_BOOKS
import com.absolutex.core.data.backup.MAX_TEXT
import kotlinx.coroutines.flow.first

private val pendingKey = stringSetPreferencesKey("backup_pending_favourites")

internal suspend fun DataStoreSettings.backupPreferences(): Map<String, Any> {
    val bag = store.data.first().toBag()
    return BackupPreferences.encode(PrefCodec.decodeApp(bag), PrefCodec.decodeReader(bag), PrefCodec.decodeRendering(bag))
}

internal suspend fun DataStoreSettings.pendingFavourites(): Set<String> =
    store.data.first()[pendingKey].orEmpty().also { validatePending(it) }

internal fun validatePending(values: Set<String>) {
    require(values.size <= MAX_BOOKS && values.all { it.length <= MAX_TEXT })
}

/** One edit for all settings and pending favourites; missing settings preserve the current value. */
internal suspend fun DataStoreSettings.restoreBackup(values: Map<String, Any>, pending: Set<String>) {
    validatePending(pending)
    store.edit { prefs ->
        val current = prefs.toBag()
        val merged = MapPrefBag(current.snapshot() + values)
        PrefCodec.encodeApp(PrefCodec.decodeApp(merged), current)
        // encodeReader omits an empty fit memory, so explicitly clear the old context choices.
        current.remove("fit_by_context")
        PrefCodec.encodeReader(PrefCodec.decodeReader(merged), current)
        PrefCodec.encodeRendering(PrefCodec.decodeRendering(merged), current)
        prefs.putAll(current)
        prefs[pendingKey] = pending
    }
}

internal suspend fun DataStoreSettings.clearPending(applied: Set<String>) {
    store.edit { prefs -> prefs[pendingKey] = prefs[pendingKey].orEmpty() - applied }
}
