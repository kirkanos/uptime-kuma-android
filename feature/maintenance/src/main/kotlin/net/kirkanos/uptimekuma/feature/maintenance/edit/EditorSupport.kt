package net.kirkanos.uptimekuma.feature.maintenance.edit

import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A selectable monitor in the editor (`pathName` like the web multiselect). */
data class MonitorOption(
    val id: Long,
    val name: String,
    val active: Boolean,
)

/** An IANA zone with its current offset (`+01:00`), as listed by the web UI's timezone dropdown. */
data class TimeZoneOption(
    val id: String,
    val offset: String,
)

internal object EditorSupport {
    private const val HOUR_MINUTES = 60L
    private val excludedZonePrefixes = listOf("Etc/", "SystemV/", "US/", "Canada/", "Mexico/", "Brazil/", "Chile/")

    /** Web UI order: active first, higher weight first, then path name. */
    fun monitorOptions(monitors: List<MonitorOverview>): List<MonitorOption> =
        monitors
            .sortedWith(
                compareBy<MonitorOverview> { !it.monitor.active }
                    .thenByDescending { it.monitor.weight }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.monitor.pathName },
            ).map { MonitorOption(it.monitor.id, it.monitor.pathName, it.monitor.active) }

    /** Region-style IANA zones sorted by current offset, then name. */
    fun timeZones(now: Instant): List<TimeZoneOption> =
        ZoneId
            .getAvailableZoneIds()
            .asSequence()
            .filter { id -> '/' in id && excludedZonePrefixes.none { id.startsWith(it) } }
            .map { id ->
                val offset = ZoneId.of(id).rules.getOffset(now)
                Triple(id, offset.totalSeconds, formatOffset(offset))
            }.sortedWith(compareBy<Triple<String, Int, String>> { it.second }.thenBy { it.first })
            .map { TimeZoneOption(it.first, it.third) }
            .toList()

    fun formatOffset(offset: ZoneOffset): String = if (offset == ZoneOffset.UTC) "+00:00" else offset.id

    /** Web UI defaults for a new maintenance: single window from now (minute precision) for one hour. */
    fun newDraft(now: LocalDateTime): MaintenanceDraft {
        val start = now.truncatedTo(ChronoUnit.MINUTES)
        return MaintenanceDraft(startDate = start, endDate = start.plusMinutes(HOUR_MINUTES))
    }

    /** Editor draft from a stored maintenance and its links. [clone] drops the id (new maintenance). */
    fun draftOf(
        detail: MaintenanceDetail,
        clone: Boolean,
    ): MaintenanceDraft {
        val m = detail.maintenance
        return MaintenanceDraft(
            id = if (clone) null else m.id,
            title = m.title,
            description = m.description,
            strategy = m.strategy,
            active = m.active,
            intervalDay = m.intervalDay,
            startDate = m.startDate,
            endDate = m.endDate,
            startTime = m.startTime ?: MaintenanceDraft.DEFAULT_START_TIME,
            endTime = m.endTime ?: MaintenanceDraft.DEFAULT_END_TIME,
            weekdays = m.weekdays.toSet(),
            daysOfMonth = m.daysOfMonth.toSet(),
            cron =
                m.cron.takeIf { m.strategy == MaintenanceStrategy.CRON && it.isNotBlank() }
                    ?: MaintenanceDraft.DEFAULT_CRON,
            durationMinutes = m.durationMinutes ?: MaintenanceDraft.DEFAULT_DURATION_MINUTES,
            timezoneOption = m.timezoneOption,
            monitorIds = detail.monitorIds.toSet(),
            statusPageIds = detail.statusPages.map { it.id }.toSet(),
        )
    }
}
