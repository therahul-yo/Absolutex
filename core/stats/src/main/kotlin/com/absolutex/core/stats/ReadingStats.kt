package com.absolutex.core.stats

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

/**
 * How long a page may sit before it stops counting as reading.
 *
 * A heuristic, and it cannot be anything else: a dense page genuinely studied for six minutes
 * and a phone left face-up on a desk look identical from here. Five minutes keeps ordinary slow
 * reading and drops the obvious walk-aways; the cost of being wrong is small in one direction
 * (a little unrecorded reading) and embarrassing in the other (an hour of "reading" while the
 * book sat open in a pocket), so it errs short.
 */
val DEFAULT_IDLE_GAP: Duration = Duration.ofMinutes(5)

/**
 * Distinct pages reached on each local date.
 *
 * Distinct per book and page, so flipping back and forth over one spread does not inflate the
 * day; re-reading the same page tomorrow counts again, because that is a different day's
 * reading. Dates come from [zone], never UTC — a page turned at 11pm belongs to that evening,
 * not to the next morning.
 */
fun pagesPerDay(events: List<PageSettled>, zone: ZoneId): Map<LocalDate, Int> {
    val seen = mutableMapOf<LocalDate, MutableSet<Pair<String, Int>>>()
    for (event in events) {
        seen.getOrPut(event.localDate(zone)) { mutableSetOf() } += event.bookKey to event.page
    }
    return seen.mapValues { (_, pages) -> pages.size }.toSortedMap()
}

/**
 * Time spent reading each book, with idle stretches removed.
 *
 * The interval between one settle and the next is time the *first* page was on screen, so it is
 * credited to that event's book. Switching books therefore needs no special case: the stretch
 * before the switch belongs to the book being left, which is exactly where it was spent.
 *
 * Two deliberate omissions. An interval longer than [idleGap] is dropped whole rather than
 * clamped, because there is no honest way to say how much of a two-hour gap was reading. And the
 * last settle of a session credits nothing, having no successor to measure against — so every
 * figure here is a slight underestimate, by at most one page's dwell per session. Inventing a
 * nominal duration for that page would trade a known small bias for an invented number.
 */
fun readingTimePerBook(
    events: List<PageSettled>,
    idleGap: Duration = DEFAULT_IDLE_GAP,
): Map<String, Duration> {
    val ordered = events.sortedBy { it.atEpochMs }
    val limit = idleGap.toMillis()
    val totals = mutableMapOf<String, Long>()
    for (index in 0 until ordered.size - 1) {
        val span = ordered[index + 1].atEpochMs - ordered[index].atEpochMs
        if (span in 0..limit) {
            totals.merge(ordered[index].bookKey, span, Long::plus)
        }
    }
    return totals.mapValues { (_, millis) -> Duration.ofMillis(millis) }
}

/** The local dates on which anything was read at all. */
fun activeDays(events: List<PageSettled>, zone: ZoneId): Set<LocalDate> =
    events.mapTo(sortedSetOf()) { it.localDate(zone) }

/**
 * Consecutive days read up to now, ending today or yesterday.
 *
 * Yesterday counts as the anchor so that a streak does not appear broken every morning before
 * the day's first page — the alternative punishes the user for the clock rather than for
 * missing a day. Two days without reading ends it.
 */
fun currentStreak(activeDays: Set<LocalDate>, today: LocalDate): Int {
    val anchor = when {
        today in activeDays -> today
        today.minusDays(1) in activeDays -> today.minusDays(1)
        else -> return 0
    }
    var day = anchor
    var length = 0
    while (day in activeDays) {
        length++
        day = day.minusDays(1)
    }
    return length
}

/** The longest run of consecutive days ever read, anywhere in the history. */
fun longestStreak(activeDays: Set<LocalDate>): Int {
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in activeDays.sorted()) {
        run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day
    }
    return longest
}

private fun PageSettled.localDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(atEpochMs).atZone(zone).toLocalDate()
