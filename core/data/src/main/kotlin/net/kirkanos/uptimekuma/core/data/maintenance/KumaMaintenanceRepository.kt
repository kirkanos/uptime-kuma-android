package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceDraft
import net.kirkanos.uptimekuma.core.model.MaintenanceSaveResult
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import javax.inject.Inject
import javax.inject.Singleton

/** [MaintenanceRepository] on top of the `maintenanceList`/`statusPageList` snapshots and the socket events. */
@Singleton
internal class KumaMaintenanceRepository
    @Inject
    constructor(
        private val backend: MaintenanceBackend,
    ) : MaintenanceRepository {
        override fun observeMaintenances(): Flow<List<Maintenance>?> =
            backend.snapshot(MAINTENANCE_LIST).map(MaintenanceJson::decodeList).distinctUntilChanged()

        override fun observeStatusPages(): Flow<List<MaintenanceStatusPage>?> =
            backend.snapshot(STATUS_PAGE_LIST).map(MaintenanceJson::decodeStatusPages).distinctUntilChanged()

        override suspend fun refresh() {
            backend.call("getMaintenanceList")
        }

        override suspend fun get(id: Long): MaintenanceDetail {
            val ack = backend.call("getMaintenance", id)
            val maintenance =
                (ack["maintenance"] as? JsonObject)?.let(MaintenanceJson::decodeMaintenance)
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "getMaintenance without maintenance"))
            val monitors = MaintenanceJson.decodeMonitorIds(backend.call("getMonitorMaintenance", id))
            val pages = MaintenanceJson.decodeLinkedStatusPages(backend.call("getMaintenanceStatusPage", id))
            return MaintenanceDetail(maintenance, monitors, pages)
        }

        override suspend fun save(draft: MaintenanceDraft): MaintenanceSaveResult {
            val payload = MaintenancePayloads.maintenance(draft)
            val ack = backend.call(if (draft.id == null) "addMaintenance" else "editMaintenance", payload)
            val id =
                ack["maintenanceID"].asLenientLong()
                    ?: draft.id
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "addMaintenance without maintenanceID"))
            // Like the web UI the links are replaced after the maintenance itself was stored. A failure here
            // leaves the maintenance in place, so it is reported instead of thrown (a retry must be an edit).
            var linkError: KumaError? = null
            val links =
                listOf(
                    "addMonitorMaintenance" to draft.monitorIds,
                    "addMaintenanceStatusPage" to draft.statusPageIds,
                )
            for ((event, ids) in links) {
                try {
                    backend.call(event, id, MaintenancePayloads.idList(ids))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: KumaException) {
                    linkError = linkError ?: e.error
                }
            }
            return MaintenanceSaveResult(id, linkError)
        }

        override suspend fun delete(id: Long) {
            backend.call("deleteMaintenance", id)
        }

        override suspend fun pause(id: Long) {
            backend.call("pauseMaintenance", id)
        }

        override suspend fun resume(id: Long) {
            backend.call("resumeMaintenance", id)
        }

        private companion object {
            const val MAINTENANCE_LIST = "maintenanceList"
            const val STATUS_PAGE_LIST = "statusPageList"
        }
    }
