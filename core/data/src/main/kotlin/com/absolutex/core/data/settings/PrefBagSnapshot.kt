package com.absolutex.core.data.settings

/**
 * Snapshot of a [MutablePrefBag]'s current values, for asserting what was persisted in tests.
 * Held as a free function so [MapPrefBag] stays within detekt's per-class function limit (the
 * lead's merged PR pushed it to 12). The only implementation is [MapPrefBag], which exposes its
 * `values` as `internal` for this access.
 */
fun MutablePrefBag.snapshot(): Map<String, Any> =
    LinkedHashMap((this as? MapPrefBag)?.values ?: emptyMap())
