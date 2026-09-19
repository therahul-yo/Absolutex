package com.absolutex.core.stats

/**
 * One page coming to rest in the reader.
 *
 * Emitted once per *settled* page, never per frame and never while a gesture is in flight: the
 * reader's draw path allocates nothing, and reading history is not worth a millisecond of it.
 *
 * [bookKey] is a `BookIdentity` key — filename and size, not a path — so a book that is renamed
 * or reached by a different route keeps one history rather than splitting into several. This
 * module deliberately does not depend on `:core:model` to say so: it stores the key and never
 * constructs one, and a dependency for a `String` would buy nothing.
 *
 * [atEpochMs] is wall-clock milliseconds. Anything derived from it that a person will read —
 * days, streaks — has to be resolved in their own time zone; see [pagesPerDay] and [activeDays].
 */
data class PageSettled(val bookKey: String, val page: Int, val atEpochMs: Long)
