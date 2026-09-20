package com.absolutex.core.stats

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the reading-history aggregation.
 *
 * Zones are fixed offsets rather than named regions on purpose: a test that depends on the
 * host's tzdata for a rule change it never meant to assert is a test that fails for the wrong
 * reason one day.
 */
class ReadingStatsTest {

    private val utc = ZoneId.of("Z")
    private val plusOne = ZoneId.of("+01:00")
    private val minusFive = ZoneId.of("-05:00")
    private val book = "Absolute Batman 001.cbr:12345"
    private val other = "Invincible 010.cbz:6789"

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun settle(iso: String, page: Int, key: String = book) =
        PageSettled(key, page, at(iso))

    // ---------------------------------------------------------------- pages per day

    @Test
    fun `no events means no days`() {
        assertEquals(emptyMap<LocalDate, Int>(), pagesPerDay(emptyList(), utc))
    }

    @Test
    fun `pages are counted once however often they are revisited`() {
        // Flipping back and forth across a spread is one page read, not three.
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 4),
            settle("2026-03-01T10:01:00Z", 5),
            settle("2026-03-01T10:02:00Z", 4),
        )
        assertEquals(mapOf(LocalDate.of(2026, 3, 1) to 2), pagesPerDay(events, utc))
    }

    @Test
    fun `the same page on two days counts on each of them`() {
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 4),
            settle("2026-03-02T10:00:00Z", 4),
        )
        assertEquals(
            mapOf(LocalDate.of(2026, 3, 1) to 1, LocalDate.of(2026, 3, 2) to 1),
            pagesPerDay(events, utc),
        )
    }

    @Test
    fun `the same page in two books is two pages`() {
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 1, book),
            settle("2026-03-01T10:05:00Z", 1, other),
        )
        assertEquals(mapOf(LocalDate.of(2026, 3, 1) to 2), pagesPerDay(events, utc))
    }

    @Test
    fun `a day is the reader's day, not UTC's`() {
        // 23:30 UTC on New Year's Eve is already New Year's Day an hour east, and still the
        // afternoon of the 31st five hours west. Bucketing this in UTC breaks a streak at the
        // wrong midnight for most of the planet.
        val newYearEve = listOf(settle("2025-12-31T23:30:00Z", 1))
        assertEquals(mapOf(LocalDate.of(2025, 12, 31) to 1), pagesPerDay(newYearEve, utc))
        assertEquals(mapOf(LocalDate.of(2026, 1, 1) to 1), pagesPerDay(newYearEve, plusOne))
        assertEquals(mapOf(LocalDate.of(2025, 12, 31) to 1), pagesPerDay(newYearEve, minusFive))
    }

    // ---------------------------------------------------------------- reading time

    @Test
    fun `consecutive settles inside the threshold accumulate`() {
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 1),
            settle("2026-03-01T10:01:00Z", 2),
            settle("2026-03-01T10:03:00Z", 3),
        )
        assertEquals(
            mapOf(book to Duration.ofMinutes(3)),
            readingTimePerBook(events),
        )
    }

    @Test
    fun `an idle gap is dropped whole, not clamped to the threshold`() {
        // Two hours away from the book contributes nothing — not five minutes of "maybe".
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 1),
            settle("2026-03-01T12:00:00Z", 2),
            settle("2026-03-01T12:02:00Z", 3),
        )
        assertEquals(mapOf(book to Duration.ofMinutes(2)), readingTimePerBook(events))
    }

    @Test
    fun `the final settle of a session credits nothing`() {
        // Deliberate: there is no successor to measure it against, so the total is a known
        // slight underestimate rather than an invented dwell.
        assertEquals(
            emptyMap<String, Duration>(),
            readingTimePerBook(listOf(settle("2026-03-01T10:00:00Z", 1))),
        )
    }

    @Test
    fun `time before a switch belongs to the book being left`() {
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 1, book),
            settle("2026-03-01T10:02:00Z", 1, other),
            settle("2026-03-01T10:05:00Z", 2, other),
        )
        assertEquals(
            mapOf(book to Duration.ofMinutes(2), other to Duration.ofMinutes(3)),
            readingTimePerBook(events),
        )
    }

    @Test
    fun `events arriving out of order are ordered before pairing`() {
        val events = listOf(
            settle("2026-03-01T10:03:00Z", 3),
            settle("2026-03-01T10:00:00Z", 1),
            settle("2026-03-01T10:01:00Z", 2),
        )
        assertEquals(mapOf(book to Duration.ofMinutes(3)), readingTimePerBook(events))
    }

    @Test
    fun `the threshold is a parameter, not a fixed rule`() {
        val events = listOf(
            settle("2026-03-01T10:00:00Z", 1),
            settle("2026-03-01T10:08:00Z", 2),
        )
        assertEquals(emptyMap<String, Duration>(), readingTimePerBook(events))
        assertEquals(
            mapOf(book to Duration.ofMinutes(8)),
            readingTimePerBook(events, Duration.ofMinutes(10)),
        )
    }

    // ---------------------------------------------------------------- streaks

    private fun days(vararg iso: String) = iso.map { LocalDate.parse(it) }.toSet()

    @Test
    fun `active days are the reader's days`() {
        val events = listOf(settle("2025-12-31T23:30:00Z", 1))
        assertEquals(days("2025-12-31"), activeDays(events, utc))
        assertEquals(days("2026-01-01"), activeDays(events, plusOne))
    }

    @Test
    fun `a streak ending today counts today`() {
        val active = days("2026-03-01", "2026-03-02", "2026-03-03")
        assertEquals(3, currentStreak(active, LocalDate.parse("2026-03-03")))
    }

    @Test
    fun `a streak survives the morning before the day's first page`() {
        // Anchoring on yesterday keeps the streak alive until the day is actually missed;
        // otherwise every streak appears broken at midnight.
        val active = days("2026-03-01", "2026-03-02", "2026-03-03")
        assertEquals(3, currentStreak(active, LocalDate.parse("2026-03-04")))
    }

    @Test
    fun `two missed days end the streak`() {
        val active = days("2026-03-01", "2026-03-02", "2026-03-03")
        assertEquals(0, currentStreak(active, LocalDate.parse("2026-03-05")))
    }

    @Test
    fun `only the run touching today counts`() {
        val active = days("2026-02-01", "2026-02-02", "2026-02-03", "2026-03-02", "2026-03-03")
        assertEquals(2, currentStreak(active, LocalDate.parse("2026-03-03")))
    }

    @Test
    fun `an empty history has no streak`() {
        assertEquals(0, currentStreak(emptySet(), LocalDate.parse("2026-03-03")))
        assertEquals(0, longestStreak(emptySet()))
    }

    @Test
    fun `the longest streak need not be the latest one`() {
        val active = days("2026-02-01", "2026-02-02", "2026-02-03", "2026-03-02", "2026-03-03")
        assertEquals(3, longestStreak(active))
    }

    @Test
    fun `a single day is a streak of one`() {
        assertEquals(1, longestStreak(days("2026-03-03")))
    }
}
