package net.kirkanos.uptimekuma.feature.maintenance.edit

import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.feature.maintenance.CronExpression

/** Problems that block saving, mirroring the web form's `required`/`min`/`max` rules plus a few server checks. */
enum class MaintenanceFieldError {
    TITLE_REQUIRED,
    TITLE_TOO_LONG,
    START_REQUIRED,
    END_REQUIRED,
    END_BEFORE_START,
    CRON_INVALID,
    DURATION_INVALID,
    INTERVAL_INVALID,
    WEEKDAYS_REQUIRED,
    DAYS_REQUIRED,

    /** Neither a monitor nor a status page (`noMonitorsOrStatusPagesSelectedError`). */
    TARGETS_REQUIRED,
}

internal object MaintenanceValidation {
    const val MAX_INTERVAL_DAYS = 3650
    private const val MAX_YEAR = 9999

    fun validate(draft: MaintenanceDraft): Set<MaintenanceFieldError> =
        buildSet {
            if (draft.title.isBlank()) add(MaintenanceFieldError.TITLE_REQUIRED)
            if (draft.title.trim().length > Maintenance.TITLE_MAX_LENGTH) add(MaintenanceFieldError.TITLE_TOO_LONG)
            addAll(scheduleErrors(draft))
            if (draft.monitorIds.isEmpty() && draft.statusPageIds.isEmpty()) {
                add(MaintenanceFieldError.TARGETS_REQUIRED)
            }
        }

    private fun scheduleErrors(draft: MaintenanceDraft): Set<MaintenanceFieldError> =
        if (draft.strategy == MaintenanceStrategy.MANUAL) emptySet() else dateErrors(draft) + strategyErrors(draft)

    private fun dateErrors(draft: MaintenanceDraft): Set<MaintenanceFieldError> =
        buildSet {
            val start = draft.startDate
            val end = draft.endDate
            val single = draft.strategy == MaintenanceStrategy.SINGLE
            if ((single && start == null) || (start?.year ?: 0) > MAX_YEAR) add(MaintenanceFieldError.START_REQUIRED)
            if ((single && end == null) || (end?.year ?: 0) > MAX_YEAR) add(MaintenanceFieldError.END_REQUIRED)
            if (start != null && end != null && !end.isAfter(start)) add(MaintenanceFieldError.END_BEFORE_START)
        }

    private fun strategyErrors(draft: MaintenanceDraft): Set<MaintenanceFieldError> =
        when (draft.strategy) {
            MaintenanceStrategy.CRON -> {
                setOfNotNull(
                    MaintenanceFieldError.CRON_INVALID.takeUnless { CronExpression.isValid(draft.cron) },
                    MaintenanceFieldError.DURATION_INVALID.takeIf { draft.durationMinutes < 1 },
                )
            }

            MaintenanceStrategy.RECURRING_INTERVAL -> {
                setOfNotNull(
                    MaintenanceFieldError.INTERVAL_INVALID.takeUnless {
                        draft.intervalDay in
                            1..MAX_INTERVAL_DAYS
                    },
                )
            }

            MaintenanceStrategy.RECURRING_WEEKDAY -> {
                setOfNotNull(MaintenanceFieldError.WEEKDAYS_REQUIRED.takeIf { draft.weekdays.isEmpty() })
            }

            MaintenanceStrategy.RECURRING_DAY_OF_MONTH -> {
                setOfNotNull(MaintenanceFieldError.DAYS_REQUIRED.takeIf { draft.daysOfMonth.isEmpty() })
            }

            MaintenanceStrategy.MANUAL, MaintenanceStrategy.SINGLE -> {
                emptySet()
            }
        }
}
