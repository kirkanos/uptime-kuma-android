package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceSaveResult
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage

/**
 * In-memory [MaintenanceRepository]. Mutations update [maintenances] roughly like the server push would.
 * Set [error] to make every suspend call fail, [linkError] to make [save] report a link failure.
 */
class FakeMaintenanceRepository(
    initial: List<Maintenance>? = emptyList(),
    initialStatusPages: List<MaintenanceStatusPage>? = emptyList(),
) : MaintenanceRepository {
    val maintenances = MutableStateFlow(initial)
    val statusPages = MutableStateFlow(initialStatusPages)

    /** Linked monitors/pages per maintenance id, returned by [get]. */
    val links = mutableMapOf<Long, Pair<List<Long>, List<Long>>>()

    var error: KumaException? = null
    var linkError: KumaError? = null
    var delayMillis: Long = 0

    var refreshCount = 0
    val savedDrafts = mutableListOf<MaintenanceDraft>()
    val getRequests = mutableListOf<Long>()
    val pausedIds = mutableListOf<Long>()
    val resumedIds = mutableListOf<Long>()
    val deletedIds = mutableListOf<Long>()
    private var nextId = 1_000L

    override fun observeMaintenances(): Flow<List<Maintenance>?> = maintenances

    override fun observeStatusPages(): Flow<List<MaintenanceStatusPage>?> = statusPages

    override suspend fun refresh() {
        simulate()
        refreshCount++
    }

    override suspend fun get(id: Long): MaintenanceDetail {
        getRequests += id
        simulate()
        val maintenance =
            maintenances.value?.firstOrNull { it.id == id }
                ?: throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Maintenance not found"))
        val (monitorIds, pageIds) = links[id] ?: (emptyList<Long>() to emptyList())
        val pages = pageIds.map { pageId -> statusPages.value?.firstOrNull { it.id == pageId } ?: page(pageId) }
        return MaintenanceDetail(maintenance, monitorIds, pages)
    }

    override suspend fun save(draft: MaintenanceDraft): MaintenanceSaveResult {
        savedDrafts += draft
        simulate()
        val id = draft.id ?: nextId++
        val stored =
            Maintenance(
                id = id,
                title = draft.title,
                description = draft.description,
                strategy = draft.strategy,
                active = draft.active,
                status = if (draft.active) MaintenanceStatus.SCHEDULED else MaintenanceStatus.INACTIVE,
                intervalDay = draft.intervalDay,
                startDate = draft.startDate,
                endDate = draft.endDate,
                startTime = draft.startTime.takeIf { draft.strategy.isRecurring },
                endTime = draft.endTime.takeIf { draft.strategy.isRecurring },
                weekdays = draft.weekdays.sorted(),
                daysOfMonth = draft.daysOfMonth.toList(),
                cron = draft.cron,
                durationMinutes = draft.durationMinutes,
                timezoneOption = draft.timezoneOption,
            )
        maintenances.value = maintenances.value.orEmpty().filterNot { it.id == id } + stored
        links[id] = draft.monitorIds.sorted() to draft.statusPageIds.sorted()
        return MaintenanceSaveResult(id, linkError)
    }

    override suspend fun delete(id: Long) {
        simulate()
        deletedIds += id
        maintenances.value = maintenances.value?.filterNot { it.id == id }
    }

    override suspend fun pause(id: Long) {
        simulate()
        pausedIds += id
        setActive(id, false)
    }

    override suspend fun resume(id: Long) {
        simulate()
        resumedIds += id
        setActive(id, true)
    }

    private fun setActive(
        id: Long,
        active: Boolean,
    ) {
        maintenances.value =
            maintenances.value?.map {
                if (it.id != id) {
                    it
                } else {
                    it.copy(
                        active = active,
                        status = if (active) MaintenanceStatus.SCHEDULED else MaintenanceStatus.INACTIVE,
                    )
                }
            }
    }

    private fun page(id: Long) = MaintenanceStatusPage(id, "Status page $id")

    private suspend fun simulate() {
        if (delayMillis > 0) delay(delayMillis)
        error?.let { throw it }
    }
}
