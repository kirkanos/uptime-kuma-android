package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

class MaintenancePayloadsTest {
    private fun json(text: String): JsonObject = KumaJson.json.parseToJsonElement(text).jsonObject

    @Test
    fun `new single window matches the web UI payload`() {
        val draft =
            MaintenanceDraft(
                title = "  DB upgrade ",
                description = "**md**",
                strategy = MaintenanceStrategy.SINGLE,
                startDate = LocalDateTime.of(2026, 10, 28, 10, 0),
                endDate = LocalDateTime.of(2026, 10, 28, 11, 0, 30),
            )
        assertEquals(
            json(
                """{"title":"DB upgrade","description":"**md**","strategy":"single","active":true,"intervalDay":1,
                "dateRange":["2026-10-28T10:00","2026-10-28T11:00"],
                "timeRange":[{"hours":2,"minutes":0},{"hours":3,"minutes":0}],
                "weekdays":[],"daysOfMonth":[],"cron":"30 3 * * *","durationMinutes":60,
                "timezoneOption":"SAME_AS_SERVER"}""",
            ),
            MaintenancePayloads.maintenance(draft),
        )
    }

    @Test
    fun `edit carries id, active flag and recurring fields`() {
        val draft =
            MaintenanceDraft(
                id = 3,
                title = "Weekly",
                strategy = MaintenanceStrategy.RECURRING_WEEKDAY,
                active = false,
                startTime = LocalTime.of(22, 30),
                endTime = LocalTime.of(1, 15),
                weekdays = setOf(5, 0, 1),
                timezoneOption = "Europe/Berlin",
                startDate = LocalDateTime.of(2026, 11, 1, 0, 0),
            )
        val payload = MaintenancePayloads.maintenance(draft)
        assertEquals(json("""{"v":3}""")["v"], payload["id"])
        assertEquals(json("""{"v":false}""")["v"], payload["active"])
        assertEquals(json("""{"v":[0,1,5]}""")["v"], payload["weekdays"])
        assertEquals(json("""{"v":[{"hours":22,"minutes":30},{"hours":1,"minutes":15}]}""")["v"], payload["timeRange"])
        assertEquals(json("""{"v":["2026-11-01T00:00",null]}""")["v"], payload["dateRange"])
        assertEquals(json("""{"v":"Europe/Berlin"}""")["v"], payload["timezoneOption"])
    }

    @Test
    fun `days of month are numbers first, then lastDay keys`() {
        val draft =
            MaintenanceDraft(
                strategy = MaintenanceStrategy.RECURRING_DAY_OF_MONTH,
                daysOfMonth = setOf(MaintenanceMonthDay.LAST, MaintenanceMonthDay.Day(15), MaintenanceMonthDay.Day(1)),
            )
        assertEquals(json("""{"v":[1,15,"lastDay1"]}""")["v"], MaintenancePayloads.maintenance(draft)["daysOfMonth"])
    }

    @Test
    fun `manual windows send no dates and new ones no id`() {
        val payload =
            MaintenancePayloads.maintenance(
                MaintenanceDraft(
                    title = "m",
                    strategy = MaintenanceStrategy.MANUAL,
                    startDate = LocalDateTime.of(2026, 1, 1, 0, 0),
                ),
            )
        assertEquals(listOf(JsonNull, JsonNull), payload["dateRange"] as List<*>)
        assertFalse(payload.containsKey("id"))
    }

    @Test
    fun `cron payload trims the expression and keeps the duration`() {
        val payload =
            MaintenancePayloads.maintenance(
                MaintenanceDraft(strategy = MaintenanceStrategy.CRON, cron = " 0 4 * * 0 ", durationMinutes = 90),
            )
        assertEquals(
            json("""{"c":"0 4 * * 0","d":90}""").values.toList(),
            listOf(payload["cron"], payload["durationMinutes"]),
        )
    }

    @Test
    fun `id lists are objects with id, sorted`() {
        assertEquals(
            json("""{"v":[{"id":1},{"id":10}]}""")["v"],
            MaintenancePayloads.idList(setOf(10L, 1L)),
        )
    }
}
