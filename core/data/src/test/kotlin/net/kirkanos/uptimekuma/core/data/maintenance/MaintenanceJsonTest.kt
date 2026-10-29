package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object MaintenanceFixtures {
    private val dir: File by lazy {
        val configured = System.getProperty("kuma.fixtures")?.let(::File)
        listOfNotNull(configured, File("../../fixtures/kuma-2.5.8")).first { it.isDirectory }
    }

    fun json(name: String): JsonObject = KumaJson.json.parseToJsonElement(File(dir, name).readText()).jsonObject

    /** Args of the first sample of a server->client event. */
    fun eventArgs(event: String): List<JsonElement> =
        json("$event.json")
            .getValue("samples")
            .jsonArray
            .first()
            .jsonObject
            .getValue("args")
            .jsonArray
            .toList()

    fun ack(name: String): JsonObject = json("ack/$name.json").getValue("response").jsonObject

    fun request(name: String): JsonArray = json("ack/$name.json").getValue("request").jsonArray
}

class MaintenanceJsonTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `decodes the recorded maintenanceList push`() {
        val list = requireNotNull(MaintenanceJson.decodeList(MaintenanceFixtures.eventArgs("maintenanceList")))
        val m = list.single()
        assertEquals(1L, m.id)
        assertEquals("Dev maintenance (single)", m.title)
        assertTrue(m.description.startsWith("Seeded by kuma-seed"))
        assertEquals(MaintenanceStrategy.SINGLE, m.strategy)
        assertTrue(m.active)
        assertEquals(MaintenanceStatus.UNDER_MAINTENANCE, m.status)
        assertEquals(LocalDateTime.of(2026, 10, 28, 23, 20, 47), m.startDate)
        assertEquals(LocalDateTime.of(2026, 10, 29, 2, 25, 47), m.endDate)
        // Single windows have no daily time range, even though Kuma sends {0,0}.
        assertNull(m.startTime)
        assertEquals("", m.cron)
        assertNull(m.durationMinutes)
        assertEquals("Europe/Berlin", m.timezone)
        assertEquals(Maintenance.TIMEZONE_SAME_AS_SERVER, m.timezoneOption)
        assertEquals("+01:00", m.timezoneOffset)
        // The single timeslot is the zone-less stored wall-clock, interpreted in the maintenance zone.
        val slot = m.timeslots.single()
        assertEquals(
            LocalDateTime
                .of(2026, 10, 28, 23, 20, 47)
                .atZone(berlin)
                .toInstant()
                .toEpochMilli(),
            slot.startMillis,
        )
        assertEquals(Instant.parse("2026-10-29T01:25:47Z").toEpochMilli(), slot.endMillis)
    }

    @Test
    fun `decodes the getMaintenance ack with null cron and zero duration`() {
        val ack = MaintenanceFixtures.ack("getMaintenance")
        val m = requireNotNull(MaintenanceJson.decodeMaintenance(ack.getValue("maintenance").jsonObject))
        assertEquals(1L, m.id)
        assertEquals("", m.cron)
        assertNull(m.durationMinutes)
        assertEquals(1, m.timeslots.size)
    }

    @Test
    fun `decodes linked monitors and status pages`() {
        assertEquals(listOf(10L), MaintenanceJson.decodeMonitorIds(MaintenanceFixtures.ack("getMonitorMaintenance")))
        assertEquals(
            listOf(MaintenanceStatusPage(1, "Dev Status")),
            MaintenanceJson.decodeLinkedStatusPages(MaintenanceFixtures.ack("getMaintenanceStatusPage")),
        )
    }

    @Test
    fun `decodes the statusPageList push`() {
        val pages = MaintenanceJson.decodeStatusPages(MaintenanceFixtures.eventArgs("statusPageList"))
        assertEquals(listOf(MaintenanceStatusPage(1, "Dev Status", "dev")), pages)
    }

    @Test
    fun `null args mean not loaded, empty object means no maintenances`() {
        assertNull(MaintenanceJson.decodeList(null))
        assertEquals(emptyList<Maintenance>(), MaintenanceJson.decodeList(listOf(JsonObject(emptyMap()))))
    }

    @Test
    fun `decodes recurring documentation example with ISO timeslots`() {
        val m = requireNotNull(MaintenanceJson.decodeMaintenance(parse(DOC_EXAMPLE)))
        assertEquals(MaintenanceStrategy.RECURRING_WEEKDAY, m.strategy)
        assertEquals(MaintenanceStatus.SCHEDULED, m.status)
        assertEquals(LocalTime.of(2, 0), m.startTime)
        assertEquals(LocalTime.of(3, 0), m.endTime)
        assertEquals(listOf(1, 3, 5), m.weekdays)
        assertEquals(60, m.durationMinutes)
        assertEquals("0 2 * * 1,3,5", m.cron)
        assertEquals(
            Instant.parse("2026-11-03T01:00:00.000Z").toEpochMilli(),
            m.timeslots.single().startMillis,
        )
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59), m.endDate)
    }

    @Test
    fun `days of month accept numbers, numeric strings and lastDay keys`() {
        val m =
            requireNotNull(
                MaintenanceJson.decodeMaintenance(
                    parse(
                        """{"id":5,"title":"x","strategy":"recurring-day-of-month","active":0,
                        "dateRange":[null],"timeRange":[{"hours":"4","minutes":"30"},{"hours":5,"minutes":0}],
                        "daysOfMonth":[1,"15","lastDay1","lastDay3","lastDay9",40],"status":"inactive"}""",
                    ),
                ),
            )
        assertEquals(
            listOf(
                MaintenanceMonthDay.Day(1),
                MaintenanceMonthDay.Day(15),
                MaintenanceMonthDay.LastDay(1),
                MaintenanceMonthDay.LastDay(3),
            ),
            m.daysOfMonth,
        )
        assertFalse(m.active)
        assertNull(m.startDate)
        assertEquals(LocalTime.of(4, 30), m.startTime)
        assertEquals(MaintenanceStatus.INACTIVE, m.status)
    }

    @Test
    fun `unknown values fall back safely`() {
        val m =
            requireNotNull(
                MaintenanceJson.decodeMaintenance(
                    parse("""{"id":"7","title":"t","strategy":"weird","status":"strange","timezone":"Not/AZone"}"""),
                ),
            )
        assertEquals(7L, m.id)
        assertEquals(MaintenanceStrategy.SINGLE, m.strategy)
        assertEquals(MaintenanceStatus.UNKNOWN, m.status)
        assertEquals(ZoneId.of("Z"), MaintenanceJson.zoneOf(m.timezone).normalized())
    }

    @Test
    fun `list is ordered like the web UI - status then title`() {
        val list =
            requireNotNull(
                MaintenanceJson.decodeList(
                    listOf(
                        parse(
                            """{
                            "1":{"id":1,"title":"b ended","status":"ended"},
                            "2":{"id":2,"title":"z active","status":"under-maintenance"},
                            "3":{"id":3,"title":"a scheduled","status":"scheduled"},
                            "4":{"id":4,"title":"A active","status":"under-maintenance"},
                            "5":{"id":5,"title":"paused","status":"inactive"},
                            "6":{"id":6,"title":"what","status":"unknown"}
                            }""",
                        ),
                    ),
                ),
            )
        assertEquals(listOf(4L, 2L, 3L, 5L, 1L, 6L), list.map { it.id })
    }

    @Test
    fun `timeslot strings in all formats Kuma produces`() {
        val utc = ZoneId.of("UTC")
        val expected = Instant.parse("2026-11-03T01:00:00Z").toEpochMilli()
        assertEquals(expected, MaintenanceJson.parseInstant("2026-11-03T01:00:00.000Z", utc))
        assertEquals(expected, MaintenanceJson.parseInstant("2026-11-03T02:00:00+01:00", utc))
        assertEquals(expected, MaintenanceJson.parseInstant("2026-11-03 02:00:00", berlin))
        assertEquals(expected, MaintenanceJson.parseInstant("2026-11-03T02:00", berlin))
        assertNull(MaintenanceJson.parseInstant("garbage", utc))
    }

    private fun parse(text: String): JsonObject = KumaJson.json.parseToJsonElement(text).jsonObject

    private companion object {
        /** `MaintenanceJSON` example from docs/kuma-api/maintenance-and-status-pages.md 1.1. */
        const val DOC_EXAMPLE = """{
          "id": 3, "title": "DB upgrade", "description": "", "strategy": "recurring-weekday", "intervalDay": 1,
          "active": true, "dateRange": ["2026-11-01 00:00:00", "2026-12-31 23:59:00"],
          "timeRange": [ {"hours": 2, "minutes": 0}, {"hours": 3, "minutes": 0} ],
          "weekdays": [1, 3, 5], "daysOfMonth": [],
          "timeslotList": [ { "startDate": "2026-11-03T01:00:00.000Z", "endDate": "2026-11-03T02:00:00.000Z" } ],
          "cron": "0 2 * * 1,3,5", "duration": 3600, "durationMinutes": 60, "timezone": "Europe/Berlin",
          "timezoneOption": "SAME_AS_SERVER", "timezoneOffset": "+01:00", "status": "scheduled"
        }"""
    }
}
