package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.model.MaintenanceTimeslot
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Decodes Kuma's `MaintenanceJSON` and related payloads (`docs/kuma-api/maintenance-and-status-pages.md`). */
internal object MaintenanceJson {
    private const val MAX_DAY_OF_MONTH = 31
    private const val MAX_WEEKDAY = 6
    private const val MAX_LAST_DAY = 4
    private const val SECONDS_PER_MINUTE = 60

    /** Web UI sort order (`ManageMaintenance.vue` statusOrderList), then title. */
    private val statusOrder =
        mapOf(
            MaintenanceStatus.UNDER_MAINTENANCE to 0,
            MaintenanceStatus.SCHEDULED to 1,
            MaintenanceStatus.INACTIVE to 2,
            MaintenanceStatus.ENDED to 3,
            MaintenanceStatus.UNKNOWN to 4,
        )

    val ordering: Comparator<Maintenance> =
        compareBy<Maintenance> { statusOrder.getValue(it.status) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.id }

    /** `maintenanceList` args: `[{ "<id>": MaintenanceJSON }]`. */
    fun decodeList(args: List<JsonElement>?): List<Maintenance>? {
        val map = args?.firstOrNull() as? JsonObject ?: return null
        return map.values.mapNotNull { (it as? JsonObject)?.let(::decodeMaintenance) }.sortedWith(ordering)
    }

    /** `statusPageList` args: `[{ "<id>": StatusPageJSON }]`. */
    fun decodeStatusPages(args: List<JsonElement>?): List<MaintenanceStatusPage>? {
        val map = args?.firstOrNull() as? JsonObject ?: return null
        return map.values
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"].asLenientLong() ?: return@mapNotNull null
                MaintenanceStatusPage(id, obj["title"].asStringOrNull().orEmpty(), obj["slug"].asStringOrNull())
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** `getMonitorMaintenance` ack: `{ok, monitors:[{id}]}`. */
    fun decodeMonitorIds(ack: JsonObject): List<Long> =
        (ack["monitors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id").asLenientLong() }

    /** `getMaintenanceStatusPage` ack: `{ok, statusPages:[{id, title}]}`. */
    fun decodeLinkedStatusPages(ack: JsonObject): List<MaintenanceStatusPage> =
        (ack["statusPages"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"].asLenientLong() ?: return@mapNotNull null
            MaintenanceStatusPage(id, obj["title"].asStringOrNull().orEmpty(), obj["slug"].asStringOrNull())
        }

    fun decodeMaintenance(obj: JsonObject): Maintenance? {
        val id = obj["id"].asLenientLong() ?: return null
        val strategy = MaintenanceStrategy.fromWire(obj["strategy"].asStringOrNull())
        val timezone = obj["timezone"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: Maintenance.TIMEZONE_UTC
        val zone = zoneOf(timezone)
        val dateRange = (obj["dateRange"] as? JsonArray).orEmpty()
        val timeRange = (obj["timeRange"] as? JsonArray).orEmpty()
        return Maintenance(
            id = id,
            title = obj["title"].asStringOrNull().orEmpty(),
            description = obj["description"].asStringOrNull().orEmpty(),
            strategy = strategy,
            active = obj["active"].asLenientBoolean() ?: true,
            status = MaintenanceStatus.fromWire(obj["status"].asStringOrNull()),
            intervalDay = obj["intervalDay"].asLenientLong()?.toInt()?.takeIf { it >= 1 } ?: 1,
            startDate = dateRange.getOrNull(0).asStringOrNull()?.let(::parseWallClock),
            endDate = dateRange.getOrNull(1).asStringOrNull()?.let(::parseWallClock),
            startTime = if (strategy.isRecurring) timeOf(timeRange.getOrNull(0)) else null,
            endTime = if (strategy.isRecurring) timeOf(timeRange.getOrNull(1)) else null,
            weekdays = weekdaysOf(obj["weekdays"]),
            daysOfMonth = daysOfMonthOf(obj["daysOfMonth"]),
            cron = obj["cron"].asStringOrNull().orEmpty(),
            durationMinutes = durationMinutesOf(obj),
            timezone = timezone,
            timezoneOption =
                obj["timezoneOption"].asStringOrNull()?.takeIf { it.isNotBlank() }
                    ?: Maintenance.TIMEZONE_SAME_AS_SERVER,
            timezoneOffset = obj["timezoneOffset"].asStringOrNull() ?: "+00:00",
            timeslots =
                (obj["timeslotList"] as? JsonArray).orEmpty().mapNotNull { slot ->
                    val s = slot as? JsonObject ?: return@mapNotNull null
                    val start = s["startDate"].asStringOrNull()?.let { parseInstant(it, zone) }
                    val end = s["endDate"].asStringOrNull()?.let { parseInstant(it, zone) }
                    if (start == null || end == null) null else MaintenanceTimeslot(start, end)
                },
        )
    }

    /**
     * `durationMinutes` is `parseInt(duration/60)` and can be `null`/`0` for single/manual; fall back to
     * `duration` (seconds) when present.
     */
    private fun durationMinutesOf(obj: JsonObject): Int? {
        val minutes = obj["durationMinutes"].asLenientLong()?.toInt()?.takeIf { it > 0 }
        return minutes ?: obj["duration"].asLenientLong()?.takeIf { it > 0 }?.let { (it / SECONDS_PER_MINUTE).toInt() }
    }

    private fun weekdaysOf(element: JsonElement?): List<Int> =
        (element as? JsonArray)
            .orEmpty()
            .mapNotNull { it.asLenientLong()?.toInt() }
            .filter { it in 0..MAX_WEEKDAY }
            .distinct()

    private fun daysOfMonthOf(element: JsonElement?): List<MaintenanceMonthDay> =
        (element as? JsonArray)
            .orEmpty()
            .mapNotNull { item ->
                val text = (item as? JsonPrimitive)?.asStringOrNull() ?: return@mapNotNull null
                if (text.startsWith(MaintenanceMonthDay.LAST_DAY_PREFIX)) {
                    text
                        .removePrefix(MaintenanceMonthDay.LAST_DAY_PREFIX)
                        .toIntOrNull()
                        ?.takeIf { it in 1..MAX_LAST_DAY }
                        ?.let { MaintenanceMonthDay.LastDay(it) }
                } else {
                    text
                        .toDoubleOrNull()
                        ?.toInt()
                        ?.takeIf { it in 1..MAX_DAY_OF_MONTH }
                        ?.let { MaintenanceMonthDay.Day(it) }
                }
            }.distinct()

    private fun timeOf(element: JsonElement?): LocalTime? {
        val obj = element as? JsonObject ?: return null
        val hours = obj["hours"].asLenientLong()?.toInt() ?: return null
        val minutes = obj["minutes"].asLenientLong()?.toInt() ?: 0
        return runCatching { LocalTime.of(hours, minutes) }.getOrNull()
    }

    fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneOffset.UTC)

    /** Zone-less `YYYY-MM-DD HH:mm[:ss]` or `YYYY-MM-DDTHH:mm[:ss]` (wall-clock in the maintenance zone). */
    fun parseWallClock(text: String): LocalDateTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed.replace(' ', 'T'))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * Timeslot boundaries: ISO instants (`...Z`) for cron/recurring, zone-less wall-clock strings
     * (the stored `start_date`/`end_date`) for single windows, interpreted in [zone].
     */
    fun parseInstant(
        text: String,
        zone: ZoneId,
    ): Long? {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.endsWith("Z") -> Instant.parse(trimmed).toEpochMilli()
                OFFSET_SUFFIX.containsMatchIn(trimmed) -> OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
                else -> parseWallClock(trimmed)?.atZone(zone)?.toInstant()?.toEpochMilli()
            }
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val OFFSET_SUFFIX = Regex("""T.*[+-]\d{2}:?\d{2}$""")
}
