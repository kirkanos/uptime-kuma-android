package net.kirkanos.uptimekuma.feature.maintenance

import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.model.MaintenanceTimeslot
import java.time.LocalDateTime
import java.time.LocalTime

/** Fixed sample maintenances for @Preview functions and unit tests (core:testing is test-only). */
@Suppress("MagicNumber")
internal object PreviewData {
    const val NOW = 1_761_700_000_000L
    private const val HOUR = 3_600_000L

    val single =
        Maintenance(
            id = 1,
            title = "Database upgrade",
            description = "Upgrading **PostgreSQL** to 17.",
            strategy = MaintenanceStrategy.SINGLE,
            status = MaintenanceStatus.UNDER_MAINTENANCE,
            startDate = LocalDateTime.of(2025, 10, 28, 23, 0),
            endDate = LocalDateTime.of(2025, 10, 29, 2, 0),
            timezone = "Europe/Berlin",
            timezoneOffset = "+01:00",
            timeslots = listOf(MaintenanceTimeslot(NOW - HOUR, NOW + 2 * HOUR)),
        )

    val weekly =
        Maintenance(
            id = 2,
            title = "Weekly patch window",
            strategy = MaintenanceStrategy.RECURRING_WEEKDAY,
            status = MaintenanceStatus.SCHEDULED,
            startTime = LocalTime.of(2, 0),
            endTime = LocalTime.of(3, 0),
            weekdays = listOf(1, 3, 5),
            startDate = LocalDateTime.of(2025, 11, 1, 0, 0),
            cron = "0 2 * * 1,3,5",
            durationMinutes = 60,
            timezone = "Europe/Berlin",
            timezoneOffset = "+01:00",
            timeslots = listOf(MaintenanceTimeslot(NOW + 20 * HOUR, NOW + 21 * HOUR)),
        )

    val monthly =
        Maintenance(
            id = 3,
            title = "Backup verification",
            strategy = MaintenanceStrategy.RECURRING_DAY_OF_MONTH,
            status = MaintenanceStatus.SCHEDULED,
            startTime = LocalTime.of(23, 30),
            endTime = LocalTime.of(0, 30),
            daysOfMonth = listOf(MaintenanceMonthDay.Day(1), MaintenanceMonthDay.Day(15), MaintenanceMonthDay.LAST),
            timezone = "UTC",
            timezoneOption = Maintenance.TIMEZONE_UTC,
            timezoneOffset = "+00:00",
            timeslots = listOf(MaintenanceTimeslot(NOW + 50 * HOUR, NOW + 51 * HOUR)),
        )

    val cron =
        Maintenance(
            id = 4,
            title = "Nightly reindex",
            strategy = MaintenanceStrategy.CRON,
            active = false,
            status = MaintenanceStatus.INACTIVE,
            cron = "30 3 * * *",
            durationMinutes = 45,
            timezone = "Europe/Berlin",
            timezoneOffset = "+01:00",
        )

    val interval =
        Maintenance(
            id = 5,
            title = "Certificate rotation",
            strategy = MaintenanceStrategy.RECURRING_INTERVAL,
            status = MaintenanceStatus.ENDED,
            intervalDay = 3,
            startTime = LocalTime.of(4, 0),
            endTime = LocalTime.of(4, 15),
            startDate = LocalDateTime.of(2025, 1, 1, 0, 0),
            endDate = LocalDateTime.of(2025, 6, 30, 0, 0),
            timezone = "Europe/Berlin",
            timezoneOffset = "+01:00",
        )

    val manual =
        Maintenance(
            id = 6,
            title = "Office move",
            strategy = MaintenanceStrategy.MANUAL,
            status = MaintenanceStatus.UNDER_MAINTENANCE,
            timezone = "Europe/Berlin",
            timezoneOffset = "+01:00",
        )

    val all: List<Maintenance> = listOf(manual, single, weekly, monthly, cron, interval)

    val statusPages =
        listOf(
            MaintenanceStatusPage(1, "Public status", "status"),
            MaintenanceStatusPage(2, "Internal", "internal"),
        )
}
