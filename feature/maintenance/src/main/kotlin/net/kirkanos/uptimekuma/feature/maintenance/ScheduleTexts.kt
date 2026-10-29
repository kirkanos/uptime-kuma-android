package net.kirkanos.uptimekuma.feature.maintenance

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.format.formatTime
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceGroup
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Weekdays in display order (Monday first, like the web UI), as Kuma numbers (0 = Sunday). */
internal val WEEKDAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

private const val DAYS_PER_WEEK = 7

internal fun formatWallClock(
    value: LocalDateTime,
    locale: Locale,
): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(value)

internal fun formatTimeOfDay(
    value: LocalTime,
    locale: Locale,
): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(value)

internal fun weekdayName(
    kumaDay: Int,
    locale: Locale,
    style: TextStyle = TextStyle.SHORT,
): String = DayOfWeek.of(if (kumaDay == 0) DAYS_PER_WEEK else kumaDay).getDisplayName(style, locale)

/** "a, b and c" in the current language. */
@Composable
internal fun joinWithAnd(items: List<String>): String =
    when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> stringResource(R.string.maintenance_schedule_and, items.dropLast(1).joinToString(", "), items.last())
    }

/** Window shown in list rows: running window, next window, manual or nothing. */
@Composable
internal fun windowText(
    maintenance: Maintenance,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (maintenance.strategy == MaintenanceStrategy.MANUAL) return stringResource(R.string.maintenance_window_manual)
    val slot = maintenance.nextTimeslot(nowMillis) ?: return stringResource(R.string.maintenance_window_none)
    val locale = currentLocale()
    val sameDay =
        Instant.ofEpochMilli(slot.startMillis).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(slot.endMillis).atZone(zone).toLocalDate()
    val end = if (sameDay) formatTime(slot.endMillis, locale, zone) else formatDateTime(slot.endMillis, locale, zone)
    return if (slot.startMillis <= nowMillis) {
        stringResource(R.string.maintenance_window_running, formatDateTime(slot.endMillis, locale, zone))
    } else {
        stringResource(R.string.maintenance_window_next, formatDateTime(slot.startMillis, locale, zone), end)
    }
}

/** `UTC+01:00 Europe/Berlin` like the web UI (zone name omitted for UTC). */
@Composable
internal fun timezoneText(maintenance: Maintenance): String =
    if (maintenance.timezone == Maintenance.TIMEZONE_UTC) {
        "UTC${maintenance.timezoneOffset}"
    } else {
        stringResource(R.string.maintenance_timezone_offset, maintenance.timezoneOffset, maintenance.timezone)
    }

/** Human description of the schedule, e.g. "Every Monday and Friday from 02:00 to 03:00". */
@Composable
internal fun scheduleSummary(maintenance: Maintenance): String {
    val locale = currentLocale()
    val start = maintenance.startTime ?: LocalTime.MIDNIGHT
    val end = maintenance.endTime ?: LocalTime.MIDNIGHT
    val from = formatTimeOfDay(start, locale)
    val to = formatTimeOfDay(end, locale)
    return when (maintenance.strategy) {
        MaintenanceStrategy.MANUAL -> {
            stringResource(R.string.maintenance_schedule_manual)
        }

        MaintenanceStrategy.SINGLE -> {
            val s = maintenance.startDate
            val e = maintenance.endDate
            if (s == null || e == null) {
                stringResource(R.string.maintenance_schedule_single_unset)
            } else {
                stringResource(
                    R.string.maintenance_schedule_single,
                    formatWallClock(s, locale),
                    formatWallClock(e, locale),
                )
            }
        }

        MaintenanceStrategy.CRON -> {
            cronSummary(maintenance.cron, maintenance.durationMinutes ?: 0)
        }

        MaintenanceStrategy.RECURRING_INTERVAL -> {
            pluralStringResource(
                R.plurals.maintenance_schedule_interval,
                maintenance.intervalDay,
                maintenance.intervalDay,
                from,
                to,
            )
        }

        MaintenanceStrategy.RECURRING_WEEKDAY -> {
            val days =
                WEEKDAY_ORDER.filter { it in maintenance.weekdays }.map {
                    weekdayName(
                        it,
                        locale,
                        TextStyle.FULL,
                    )
                }
            val list = joinWithAnd(days).ifEmpty { stringResource(R.string.maintenance_schedule_no_days) }
            stringResource(R.string.maintenance_schedule_weekday, list, from, to)
        }

        MaintenanceStrategy.RECURRING_DAY_OF_MONTH -> {
            stringResource(R.string.maintenance_schedule_day_of_month, monthDaysText(maintenance.daysOfMonth), from, to)
        }
    }
}

@Composable
internal fun monthDaysText(days: Collection<MaintenanceMonthDay>): String {
    val numbers = days.filterIsInstance<MaintenanceMonthDay.Day>().map { it.day }.sorted()
    val lastDay = days.any { it is MaintenanceMonthDay.LastDay }
    return monthDaysText(numbers, lastDay)
}

@Composable
private fun monthDaysText(
    numbers: List<Int>,
    lastDay: Boolean,
): String {
    val formatted = numbers.map { stringResource(R.string.maintenance_schedule_day_number, it) }
    val numberPart =
        if (formatted.isEmpty()) {
            null
        } else {
            val joined = if (lastDay) formatted.joinToString(", ") else joinWithAnd(formatted)
            stringResource(R.string.maintenance_schedule_day_list, joined)
        }
    val last = if (lastDay) stringResource(R.string.maintenance_schedule_last_day) else null
    return when {
        numberPart != null && last != null -> stringResource(R.string.maintenance_schedule_and, numberPart, last)
        numberPart != null -> numberPart
        last != null -> last
        else -> stringResource(R.string.maintenance_schedule_no_days)
    }
}

/** Plain-language cron description where possible, else the raw expression. */
@Composable
internal fun cronSummary(
    cron: String,
    durationMinutes: Int,
): String {
    val duration = pluralStringResource(R.plurals.maintenance_minutes, durationMinutes, durationMinutes)
    val described = cronDescription(cron)
    return if (described != null) {
        stringResource(R.string.maintenance_schedule_cron_described, described, cron, duration)
    } else {
        stringResource(R.string.maintenance_schedule_cron, cron, duration)
    }
}

/** e.g. "Every day at 03:30"; `null` for expressions without a simple wording. */
@Composable
internal fun cronDescription(cron: String): String? {
    val locale = currentLocale()
    return when (val summary = CronExpression.summarize(cron)) {
        null -> {
            null
        }

        is CronExpression.Summary.Daily -> {
            stringResource(R.string.maintenance_cron_daily, formatTimeOfDay(summary.time, locale))
        }

        is CronExpression.Summary.Weekly -> {
            val days = WEEKDAY_ORDER.filter { it in summary.weekdays }.map { weekdayName(it, locale, TextStyle.FULL) }
            stringResource(R.string.maintenance_cron_weekly, formatTimeOfDay(summary.time, locale), joinWithAnd(days))
        }

        is CronExpression.Summary.Monthly -> {
            stringResource(
                R.string.maintenance_cron_monthly,
                formatTimeOfDay(summary.time, locale),
                monthDaysText(summary.days, summary.lastDay),
            )
        }
    }
}

/** "Effective …" line for recurring/cron windows with a date range, `null` when unbounded. */
@Composable
internal fun effectiveRangeText(maintenance: Maintenance): String? {
    if (maintenance.strategy == MaintenanceStrategy.MANUAL || maintenance.strategy == MaintenanceStrategy.SINGLE) {
        return null
    }
    val locale = currentLocale()
    val start = maintenance.startDate?.let { formatWallClock(it, locale) }
    val end = maintenance.endDate?.let { formatWallClock(it, locale) }
    return when {
        start != null && end != null -> stringResource(R.string.maintenance_effective_range, start, end)
        start != null -> stringResource(R.string.maintenance_effective_from, start)
        end != null -> stringResource(R.string.maintenance_effective_until, end)
        else -> null
    }
}
