package com.absolutex.core.data.backup

import org.json.JSONArray
import org.json.JSONObject

internal object BackupWriter {
    fun write(data: BackupData): ByteArray {
        if (!data.withinExportLimits()) throw BackupCapacityExceeded()
        BackupCodec.validate(data)
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION).put("appVersion", data.appVersion)
            .put("progress", JSONArray(data.progress.map {
                JSONObject().put("bookId", it.bookId).put("pageIndex", it.pageIndex)
                    .put("pageCount", it.pageCount).put("updatedAt", it.updatedAt)
            }))
            .put("history", JSONArray(data.history.map {
                JSONObject().put("bookId", it.bookKey).put("pageIndex", it.page).put("atEpochMs", it.atEpochMs)
            }))
            .put("bookmarks", JSONArray(data.bookmarks.map {
                JSONObject().put("bookId", it.bookId).put("pageIndex", it.pageIndex).put("createdAt", it.createdAt)
            }))
            .put("bookPrefs", JSONArray(data.bookPrefs.map {
                JSONObject().put("bookId", it.bookId).put("readingFlow", it.readingFlow ?: JSONObject.NULL)
                    .put("pageLayout", it.pageLayout ?: JSONObject.NULL)
            }))
            .put("favourites", JSONArray(data.favourites.sorted()))
        val prefs = JSONObject()
        data.preferences.forEach { (key, value) ->
            prefs.put(key, if (value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value)
        }
        root.put("preferences", prefs)
        val bytes = (root.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) throw BackupCapacityExceeded()
        // The exporter obeys exactly the same limits as the importer, before opening a destination.
        BackupCodec.read(bytes.inputStream())
        return bytes
    }
}
