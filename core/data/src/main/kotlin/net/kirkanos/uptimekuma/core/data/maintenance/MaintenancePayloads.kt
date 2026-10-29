package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Builds what the web UI sends (`EditMaintenance.vue`, `Maintenance.jsonToBean`): the full maintenance object
 * for `addMaintenance`/`editMaintenance` and the `[{id}]` arrays for the link events.
 */
internal object MaintenancePayloads {
    /** Format of `<input type="datetime-local">`, which is what the web UI submits. */
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun maintenance(draft: MaintenanceDraft): JsonObject =
        buildJsonObject {
            draft.id?.let { put("id", it) }
            put("title", draft.title.trim())
            put("description", draft.description)
            put("strategy", draft.strategy.wire)
            put("active", draft.active)
            put("intervalDay", draft.intervalDay)
            put("dateRange", dateRange(draft))
            putJsonArray("timeRange") {
                add(time(draft.startTime))
                add(time(draft.endTime))
            }
            putJsonArray("weekdays") { draft.weekdays.sorted().forEach { add(it) } }
            putJsonArray("daysOfMonth") { monthDays(draft.daysOfMonth).forEach { add(it) } }
            put("cron", draft.cron.trim())
            put("durationMinutes", draft.durationMinutes)
            put("timezoneOption", draft.timezoneOption)
        }

    /** `[{id}, ...]` for `addMonitorMaintenance` / `addMaintenanceStatusPage`. */
    fun idList(ids: Collection<Long>): JsonArray =
        buildJsonArray {
            ids.sorted().forEach { id -> add(buildJsonObject { put("id", id) }) }
        }

    /** Manual windows have no dates; the server stores `null` for missing entries. */
    private fun dateRange(draft: MaintenanceDraft): JsonArray =
        if (draft.strategy == MaintenanceStrategy.MANUAL) {
            JsonArray(listOf(JsonNull, JsonNull))
        } else {
            JsonArray(listOf(dateOrNull(draft.startDate), dateOrNull(draft.endDate)))
        }

    private fun dateOrNull(value: LocalDateTime?) = value?.let { JsonPrimitive(DATE_TIME.format(it)) } ?: JsonNull

    private fun time(value: LocalTime): JsonObject =
        buildJsonObject {
            put("hours", value.hour)
            put("minutes", value.minute)
        }

    /** Day numbers ascending, then `lastDayN` strings (only `lastDay1` is honoured by the server). */
    private fun monthDays(days: Set<MaintenanceMonthDay>): List<JsonPrimitive> {
        val numbers =
            days
                .filterIsInstance<MaintenanceMonthDay.Day>()
                .map { it.day }
                .sorted()
                .map(::JsonPrimitive)
        val last = days.filterIsInstance<MaintenanceMonthDay.LastDay>().map { it.n }.sorted()
        return numbers + last.map { JsonPrimitive("${MaintenanceMonthDay.LAST_DAY_PREFIX}$it") }
    }
}
