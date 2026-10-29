package net.kirkanos.uptimekuma.feature.maintenance.edit

import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.feature.maintenance.PreviewData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class EditorLogicTest {
    private val valid =
        MaintenanceDraft(
            title = "t",
            startDate = LocalDateTime.of(2026, 11, 1, 10, 0),
            endDate = LocalDateTime.of(2026, 11, 1, 11, 0),
            monitorIds = setOf(1),
        )

    private fun errors(draft: MaintenanceDraft) = MaintenanceValidation.validate(draft)

    @Test
    fun `valid single window has no errors`() {
        assertEquals(emptySet<MaintenanceFieldError>(), errors(valid))
    }

    @Test
    fun `title is required and limited to 150 characters`() {
        assertEquals(setOf(MaintenanceFieldError.TITLE_REQUIRED), errors(valid.copy(title = "  ")))
        assertEquals(setOf(MaintenanceFieldError.TITLE_TOO_LONG), errors(valid.copy(title = "x".repeat(151))))
        assertEquals(emptySet<MaintenanceFieldError>(), errors(valid.copy(title = "x".repeat(150))))
    }

    @Test
    fun `single needs both dates in order`() {
        assertEquals(
            setOf(MaintenanceFieldError.START_REQUIRED, MaintenanceFieldError.END_REQUIRED),
            errors(valid.copy(startDate = null, endDate = null)),
        )
        assertEquals(
            setOf(MaintenanceFieldError.END_BEFORE_START),
            errors(valid.copy(endDate = valid.startDate)),
        )
    }

    @Test
    fun `manual ignores dates entirely`() {
        assertEquals(
            emptySet<MaintenanceFieldError>(),
            errors(valid.copy(strategy = MaintenanceStrategy.MANUAL, startDate = null, endDate = valid.startDate)),
        )
    }

    @Test
    fun `recurring date range is optional but ordered`() {
        val weekly = valid.copy(strategy = MaintenanceStrategy.RECURRING_WEEKDAY, weekdays = setOf(1))
        assertEquals(emptySet<MaintenanceFieldError>(), errors(weekly.copy(startDate = null, endDate = null)))
        assertEquals(
            setOf(MaintenanceFieldError.END_BEFORE_START),
            errors(weekly.copy(endDate = LocalDateTime.of(2026, 1, 1, 0, 0))),
        )
        assertEquals(setOf(MaintenanceFieldError.WEEKDAYS_REQUIRED), errors(weekly.copy(weekdays = emptySet())))
    }

    @Test
    fun `strategy specific rules`() {
        val cron = valid.copy(strategy = MaintenanceStrategy.CRON)
        assertEquals(emptySet<MaintenanceFieldError>(), errors(cron))
        assertEquals(
            setOf(MaintenanceFieldError.CRON_INVALID, MaintenanceFieldError.DURATION_INVALID),
            errors(cron.copy(cron = "61 * * * *", durationMinutes = 0)),
        )
        val interval = valid.copy(strategy = MaintenanceStrategy.RECURRING_INTERVAL)
        assertEquals(setOf(MaintenanceFieldError.INTERVAL_INVALID), errors(interval.copy(intervalDay = 0)))
        assertEquals(setOf(MaintenanceFieldError.INTERVAL_INVALID), errors(interval.copy(intervalDay = 3651)))
        assertEquals(emptySet<MaintenanceFieldError>(), errors(interval.copy(intervalDay = 3650)))
        val monthly = valid.copy(strategy = MaintenanceStrategy.RECURRING_DAY_OF_MONTH)
        assertEquals(setOf(MaintenanceFieldError.DAYS_REQUIRED), errors(monthly))
        assertEquals(
            emptySet<MaintenanceFieldError>(),
            errors(monthly.copy(daysOfMonth = setOf(MaintenanceMonthDay.LAST))),
        )
    }

    @Test
    fun `needs a monitor or a status page`() {
        assertEquals(setOf(MaintenanceFieldError.TARGETS_REQUIRED), errors(valid.copy(monitorIds = emptySet())))
        assertEquals(
            emptySet<MaintenanceFieldError>(),
            errors(valid.copy(monitorIds = emptySet(), statusPageIds = setOf(1))),
        )
    }

    @Test
    fun `monitor options are sorted like the web UI`() {
        fun overview(
            id: Long,
            name: String,
            active: Boolean = true,
            weight: Int = 0,
        ) = MonitorOverview(
            Monitor(id = id, name = name, type = "http", active = active, weight = weight),
            status = null,
        )
        val options =
            EditorSupport.monitorOptions(
                listOf(
                    overview(1, "b"),
                    overview(2, "a", active = false),
                    overview(3, "c", weight = 5),
                    overview(4, "A"),
                ),
            )
        assertEquals(listOf(3L, 4L, 1L, 2L), options.map { it.id })
        assertEquals(MonitorOption(2, "a", active = false), options.last())
    }

    @Test
    fun `new draft uses web UI defaults`() {
        val draft = EditorSupport.newDraft(LocalDateTime.of(2026, 10, 29, 14, 7, 31, 99))
        assertEquals(LocalDateTime.of(2026, 10, 29, 14, 7), draft.startDate)
        assertEquals(LocalDateTime.of(2026, 10, 29, 15, 7), draft.endDate)
        assertEquals(MaintenanceStrategy.SINGLE, draft.strategy)
        assertEquals("30 3 * * *", draft.cron)
        assertEquals(60, draft.durationMinutes)
        assertEquals(LocalTime.of(2, 0), draft.startTime)
        assertEquals(Maintenance.TIMEZONE_SAME_AS_SERVER, draft.timezoneOption)
        assertNull(draft.id)
    }

    @Test
    fun `draft from a stored maintenance keeps everything, clone drops the id`() {
        val detail =
            MaintenanceDetail(
                PreviewData.weekly.copy(timezoneOption = "Europe/Berlin"),
                monitorIds = listOf(3, 1),
                statusPages = listOf(MaintenanceStatusPage(2, "x")),
            )
        val draft = EditorSupport.draftOf(detail, clone = false)
        assertEquals(2L, draft.id)
        assertEquals(setOf(1, 3, 5), draft.weekdays)
        assertEquals(LocalTime.of(2, 0), draft.startTime)
        assertEquals(setOf(1L, 3L), draft.monitorIds)
        assertEquals(setOf(2L), draft.statusPageIds)
        assertEquals("Europe/Berlin", draft.timezoneOption)
        // The generated cron of a recurring maintenance is not carried over as the editable cron.
        assertEquals(MaintenanceDraft.DEFAULT_CRON, draft.cron)
        assertNull(EditorSupport.draftOf(detail, clone = true).id)

        val cron = EditorSupport.draftOf(MaintenanceDetail(PreviewData.cron, emptyList(), emptyList()), clone = false)
        assertEquals("30 3 * * *", cron.cron)
        assertEquals(45, cron.durationMinutes)
        assertEquals(false, cron.active)
    }

    @Test
    fun `time zones are region zones sorted by offset with formatted offsets`() {
        val zones = EditorSupport.timeZones(Instant.parse("2026-01-15T00:00:00Z"))
        val berlin = zones.first { it.id == "Europe/Berlin" }
        assertEquals("+01:00", berlin.offset)
        assertTrue(zones.none { it.id.startsWith("Etc/") || '/' !in it.id })
        val offsets = zones.map { it.offset.replace(":", "").toInt() }
        assertEquals(offsets.sorted(), offsets)
        assertEquals("+00:00", zones.first { it.id == "Europe/London" }.offset)
    }
}
