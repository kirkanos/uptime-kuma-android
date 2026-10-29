package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceSaveResult
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage

/**
 * Maintenance windows. Lists come from the server-pushed `maintenanceList` / `statusPageList`
 * (kept in memory, not cached in Room). Suspend functions throw
 * [net.kirkanos.uptimekuma.core.model.KumaException] on failure.
 */
interface MaintenanceRepository {
    /** All maintenances, ordered like the web UI (status, then title). `null` until the server sent the list. */
    fun observeMaintenances(): Flow<List<Maintenance>?>

    /** Status pages that can show maintenance info, ordered by title. `null` until received. */
    fun observeStatusPages(): Flow<List<MaintenanceStatusPage>?>

    /** Asks the server to push `maintenanceList` again (connects transiently if needed). */
    suspend fun refresh()

    /** `getMaintenance` + `getMonitorMaintenance` + `getMaintenanceStatusPage`. */
    suspend fun get(id: Long): MaintenanceDetail

    /**
     * `addMaintenance` (draft id null) or `editMaintenance`, then replaces the monitor and status page
     * links. Throws when the maintenance itself could not be stored.
     */
    suspend fun save(draft: MaintenanceDraft): MaintenanceSaveResult

    suspend fun delete(id: Long)

    suspend fun pause(id: Long)

    suspend fun resume(id: Long)
}
