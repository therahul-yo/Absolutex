package com.absolutex.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.absolutex.core.data.backup.BackupPreferences
import com.absolutex.core.data.backup.MAX_PENDING_FAVOURITES
import com.absolutex.core.data.backup.validBackupIdentity
import kotlinx.coroutines.flow.first

private val pendingKey = stringSetPreferencesKey("backup_pending_favourites")
private val pendingTimesKey = stringSetPreferencesKey("backup_pending_favourite_times")

internal data class SettingsChanges(val addedPending: Set<String>, val changed: Boolean, val dropped: Int)

private fun Preferences.backupValues(): Map<String, Any> {
    val bag = toBag()
    return BackupPreferences.encode(
        PrefCodec.decodeApp(bag), PrefCodec.decodeReader(bag), PrefCodec.decodeRendering(bag),
    )
}

internal suspend fun DataStoreSettings.backupPreferences(): Map<String, Any> = store.data.first().backupValues()

internal suspend fun DataStoreSettings.pendingFavourites(): Set<String> =
    store.data.first()[pendingKey].orEmpty().filter(::validBackupIdentity).toSet()

/** One edit for settings and unmatched favourites. Oldest pending entries yield to newly restored ones. */
internal suspend fun DataStoreSettings.restoreBackup(
    values: Map<String, Any>, pending: Set<String>, now: Long = System.currentTimeMillis(),
): SettingsChanges {
    var result = SettingsChanges(emptySet(), false, 0)
    store.edit { prefs ->
        val before = prefs.backupValues()
        val oldPending = prefs[pendingKey].orEmpty()
        val times = prefs[pendingTimesKey].orEmpty().associate { entry ->
            entry.substringAfter('|') to (entry.substringBefore('|').toLongOrNull() ?: 0L)
        }
        val retained = pending.filter(::validBackupIdentity).sortedWith(
            compareByDescending<String> { if (it in oldPending) times[it] ?: 0L else now }.thenBy { it },
        ).take(MAX_PENDING_FAVOURITES).toSet()
        val current = prefs.toBag()
        val merged = MapPrefBag(current.snapshot() + values.filterKeys { it in before })
        PrefCodec.encodeApp(PrefCodec.decodeApp(merged), current)
        // encodeReader omits an empty fit memory, so explicitly clear the old context choices.
        current.remove("fit_by_context")
        PrefCodec.encodeReader(PrefCodec.decodeReader(merged), current)
        PrefCodec.encodeRendering(PrefCodec.decodeRendering(merged), current)
        prefs.putAll(current)
        prefs[pendingKey] = retained
        prefs[pendingTimesKey] = retained.map { "${if (it in oldPending) times[it] ?: 0L else now}|$it" }.toSet()
        result = SettingsChanges(retained - oldPending, before != prefs.backupValues(), pending.size - retained.size)
    }
    return result
}

internal suspend fun DataStoreSettings.clearPending(applied: Set<String>) {
    store.edit { prefs ->
        prefs[pendingKey] = prefs[pendingKey].orEmpty() - applied
        prefs[pendingTimesKey] = prefs[pendingTimesKey].orEmpty().filterNot {
            it.substringAfter('|') in applied
        }.toSet()
    }
}

internal suspend fun DataStoreSettings.pendingFavouriteAges(): Map<String, Long> {
    val prefs = store.data.first()
    val times = prefs[pendingTimesKey].orEmpty().associate {
        it.substringAfter('|') to (it.substringBefore('|').toLongOrNull() ?: 0L)
    }
    return prefs[pendingKey].orEmpty().filter(::validBackupIdentity).associateWith { times[it] ?: 0L }
}
