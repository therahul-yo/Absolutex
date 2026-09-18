package com.absolutex.remote.sync

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Runs [parse] over [body], turning malformed JSON into IOException (never a raw JSONException). */
internal fun <T> parseJson(body: String, parse: (JSONObject) -> T): T {
    return try {
        parse(JSONObject(body))
    } catch (e: JSONException) {
        throw IOException("malformed JSON", e)
    }
}

/** Runs [parse] over a bare JSON array [body], same IOException contract as [parseJson]. */
internal fun <T> parseJsonArray(body: String, parse: (JSONArray) -> T): T {
    return try {
        parse(JSONArray(body))
    } catch (e: JSONException) {
        throw IOException("malformed JSON", e)
    }
}

/** Required field: absent or null means the server broke its contract → IOException, never NPE. */
internal fun <T> req(obj: JSONObject, key: String, get: (String) -> T): T {
    if (!obj.has(key) || obj.isNull(key)) throw IOException("missing \"$key\"")
    return get(key)
}

/** Required object array; the List-builder avoids an index loop over the JSONArray. */
internal fun reqObjects(obj: JSONObject, key: String): List<JSONObject> {
    val array = req(obj, key, obj::getJSONArray)
    return List(array.length(), array::getJSONObject)
}

/** ISO-8601 → epoch millis; unparseable stamps are contract breaks, not crashes. */
internal fun parseInstant(value: String): Long {
    return try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        throw IOException("bad timestamp \"$value\"", e)
    }
}

/** Optional ISO-8601: absent/null degrades to epoch 0 so any local write wins (documented). */
internal fun optInstant(obj: JSONObject, key: String): Long {
    if (!obj.has(key) || obj.isNull(key)) return 0L
    return parseInstant(obj.getString(key))
}

/** Optional long: absent/null (or a server that omits it) degrades to null, never a crash. */
internal fun optLong(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return obj.getLong(key)
}

/** Optional int with the same degrade-to-null contract. */
internal fun optInt(obj: JSONObject, key: String): Int? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return obj.getInt(key)
}

/** Optional object array: absent/null degrades to empty, never a crash. */
internal fun optObjects(obj: JSONObject, key: String): List<JSONObject> {
    if (!obj.has(key) || obj.isNull(key)) return emptyList()
    val array = obj.getJSONArray(key)
    return List(array.length(), array::getJSONObject)
}

/** Optional string with the same degrade-to-null contract. */
internal fun optString(obj: JSONObject, key: String): String? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return obj.getString(key)
}
