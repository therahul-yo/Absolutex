package com.absolutex.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the folder scans are doing right now, keyed by location (a SAF tree Uri string).
 *
 * In memory only, and never logged: a location is a Uri, and Uris stay out of logcat. [unreadable]
 * holds the locations whose last scan could not read something, which is how the library tells
 * "this folder is empty" from "this folder could not be read".
 */
data class ScanSnapshot(
    val scanning: Set<String> = emptySet(),
    val unreadable: Set<String> = emptySet(),
)

/**
 * The bridge between the app shell, which runs scans, and the library screen, which explains what
 * they found. A singleton so both see the same state without either depending on the other.
 */
@Singleton
class ScanStatus @Inject constructor() {

    private val _state = MutableStateFlow(ScanSnapshot())
    val state: StateFlow<ScanSnapshot> = _state.asStateFlow()

    /** Marks [locations] as scanning and forgets any earlier failure: the answer is being redone. */
    fun started(locations: Collection<String>) = _state.update {
        it.copy(scanning = it.scanning + locations, unreadable = it.unreadable - locations.toSet())
    }

    /** A scan of [location] ended; [unreadable] is whether any folder in it could not be read. */
    fun finished(location: String, unreadable: Boolean) = _state.update {
        it.copy(
            scanning = it.scanning - location,
            unreadable = if (unreadable) it.unreadable + location else it.unreadable - location,
        )
    }

    /**
     * Stops reporting [locations] as scanning without judging them, for a scan that was cancelled
     * or never reached. Idempotent, so it is safe in a `finally` after [finished] already ran.
     */
    fun abandoned(locations: Collection<String>) = _state.update {
        it.copy(scanning = it.scanning - locations.toSet())
    }
}

/**
 * Counts the folders a walk could not read. Thread-safe: a walk reads on an IO thread and the
 * caller reads the verdict after it, and this holds no Uri and no exception, only the fact.
 */
class ReadFailures {
    private val count = AtomicInteger()

    fun record() {
        count.incrementAndGet()
    }

    val any: Boolean get() = count.get() > 0
}
