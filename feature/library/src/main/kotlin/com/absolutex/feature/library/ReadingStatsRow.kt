package com.absolutex.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.core.data.ReadingHistory
import com.absolutex.core.stats.activeDays
import com.absolutex.core.stats.currentStreak
import com.absolutex.core.stats.pagesPerDay
import com.absolutex.core.stats.readingTimePerBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The week at a glance: pages read in the last seven days, the current streak, time read. */
data class WeekStats(val pages: Int, val streakDays: Int, val minutes: Long)

/** Reads the history once per showing; stats are a glance, not a live counter. */
@HiltViewModel
class ReadingStatsViewModel @Inject constructor(private val history: ReadingHistory) : ViewModel() {
    fun week(): Flow<WeekStats?> = flow {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val since = today.minusDays(STREAK_LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = runCatching { history.historySince(since) }.getOrDefault(emptyList())
        if (events.isEmpty()) {
            emit(null)
            return@flow
        }
        val weekStart = today.minusDays(WEEK_DAYS - 1)
        val pages = pagesPerDay(events, zone).filterKeys { !it.isBefore(weekStart) }.values.sum()
        val weekStartMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val thisWeek = events.filter { it.atEpochMs >= weekStartMs }
        val minutes = readingTimePerBook(thisWeek).values.sumOf { it.toMinutes() }
        emit(WeekStats(pages, currentStreak(activeDays(events, zone), today), minutes))
    }
}

/**
 * Three quiet figures over Recent: pages this week, the reading streak, and time read. Absent
 * until there is any history, so a new reader is never greeted by a row of zeros.
 */
@Composable
internal fun ReadingStatsRow(modifier: Modifier = Modifier, vm: ReadingStatsViewModel = hiltViewModel()) {
    val stats by vm.week().collectAsStateWithLifecycle(null)
    val week = stats ?: return
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.Edge),
        horizontalArrangement = Arrangement.spacedBy(Space.Gap),
    ) {
        Stat(
            Icons.Outlined.AutoStories,
            pluralStringResource(R.plurals.library_stats_pages, week.pages, week.pages),
            Modifier.weight(1f),
        )
        Stat(
            Icons.Outlined.LocalFireDepartment,
            pluralStringResource(R.plurals.library_stats_streak, week.streakDays, week.streakDays),
            Modifier.weight(1f),
        )
        Stat(
            Icons.Outlined.Schedule,
            stringResource(R.string.library_stats_time, week.minutes / MINUTES, week.minutes % MINUTES),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(Space.Row), verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val WEEK_DAYS = 7L
private const val STREAK_LOOKBACK_DAYS = 60L
private const val MINUTES = 60
