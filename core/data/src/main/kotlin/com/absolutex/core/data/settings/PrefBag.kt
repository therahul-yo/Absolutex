package com.absolutex.core.data.settings

/**
 * A typed key/value store, one step removed from DataStore.
 *
 * The seam exists so the defaulting and validation in [PrefCodec] is ordinary Kotlin: it runs in
 * milliseconds under plain JUnit instead of needing Robolectric to stand up a `Context`, and the
 * "corrupt store falls back to defaults" requirement becomes a test you can actually read. The
 * cost is one indirection on a path that runs once per settings read, which is never hot.
 *
 * Implementations MUST return null rather than throw when a key holds a value of a different
 * type than the one asked for. DataStore's own `Preferences[key]` throws `ClassCastException`
 * there, and a store written by an older build of the app is exactly where that happens.
 */
interface PrefBag {
    fun boolean(key: String): Boolean?
    fun int(key: String): Int?
    fun string(key: String): String?
    fun stringSet(key: String): Set<String>?
}

/** Write side of [PrefBag]. Separate so the reader can depend on reads alone. */
interface MutablePrefBag : PrefBag {
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String)
    fun putStringSet(key: String, value: Set<String>)
}

/**
 * In-memory [MutablePrefBag]. Used as the test double, and as the implementation the settings and
 * reader screens can run against before the DataStore adapter lands.
 *
 * Type mismatches resolve to null exactly as the contract requires, so a test can seed a wrongly
 * typed value and see the same fallback a migrated store would produce.
 */
class MapPrefBag(initial: Map<String, Any> = emptyMap()) : MutablePrefBag {

    private val values: MutableMap<String, Any> = LinkedHashMap(initial)

    /** Snapshot, for asserting what was persisted. */
    fun snapshot(): Map<String, Any> = LinkedHashMap(values)

    override fun boolean(key: String): Boolean? = values[key] as? Boolean

    override fun int(key: String): Int? = values[key] as? Int

    override fun string(key: String): String? = values[key] as? String

    override fun stringSet(key: String): Set<String>? {
        val raw = values[key] as? Collection<*> ?: return null
        // A heterogeneous collection is a corrupt entry, not a set of extensions: drop it whole
        // rather than silently keeping the half of it that happens to be strings.
        if (raw.any { it !is String }) return null
        return raw.mapTo(LinkedHashSet()) { it as String }
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun putStringSet(key: String, value: Set<String>) {
        values[key] = LinkedHashSet(value)
    }
}
