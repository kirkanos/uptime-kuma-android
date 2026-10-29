package net.kirkanos.uptimekuma.feature.maintenance.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceDetail
import net.kirkanos.uptimekuma.core.model.MaintenanceStatusPage
import net.kirkanos.uptimekuma.feature.maintenance.MaintenanceDetailRoute
import net.kirkanos.uptimekuma.feature.maintenance.list.ActionResult
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceAction
import net.kirkanos.uptimekuma.feature.maintenance.list.runCatchingKuma
import javax.inject.Inject

/** An affected monitor; [name]/[status] are null when the monitor is not (or no longer) in the local list. */
data class AffectedMonitor(
    val id: Long,
    val name: String?,
    val status: DisplayStatus?,
)

data class MaintenanceDetailUiState(
    val loading: Boolean = true,
    val maintenance: Maintenance? = null,
    val monitors: List<AffectedMonitor> = emptyList(),
    val statusPages: List<MaintenanceStatusPage> = emptyList(),
    /** Error of loading the links (or the maintenance itself when it is not in the pushed list). */
    val loadError: KumaError? = null,
    val actionInProgress: MaintenanceAction? = null,
    val actionResult: ActionResult? = null,
    val deleted: Boolean = false,
) {
    val notFound: Boolean get() = !loading && maintenance == null && loadError == null && !deleted
}

private data class DetailLocal(
    val detail: MaintenanceDetail? = null,
    val loading: Boolean = true,
    val loadError: KumaError? = null,
    val actionInProgress: MaintenanceAction? = null,
    val actionResult: ActionResult? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class MaintenanceDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MaintenanceRepository,
        monitorRepository: MonitorRepository,
    ) : ViewModel() {
        val maintenanceId: Long = checkNotNull(savedStateHandle.get<Long>(MaintenanceDetailRoute.ARG_ID))

        private val local = MutableStateFlow(DetailLocal())

        val state: StateFlow<MaintenanceDetailUiState> =
            combine(
                repository.observeMaintenances(),
                monitorRepository.observeMonitors(),
                local,
            ) { list, monitors, local ->
                // The pushed list is live (status changes at window start/end); the fetched detail is the fallback.
                val live = list?.firstOrNull { it.id == maintenanceId }
                val removed = list != null && live == null && local.detail != null
                val maintenance = live ?: local.detail?.maintenance?.takeUnless { removed }
                val byId = monitors.associateBy { it.monitor.id }
                MaintenanceDetailUiState(
                    loading = local.loading && maintenance == null,
                    maintenance = maintenance,
                    monitors =
                        local.detail?.monitorIds.orEmpty().map { id ->
                            val overview = byId[id]
                            AffectedMonitor(id, overview?.monitor?.pathName, overview?.displayStatus)
                        },
                    statusPages = local.detail?.statusPages.orEmpty(),
                    loadError = local.loadError,
                    actionInProgress = local.actionInProgress,
                    actionResult = local.actionResult,
                    deleted = local.deleted,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MaintenanceDetailUiState())

        /** Loads maintenance, affected monitors and status pages; called whenever the screen is shown. */
        fun load() {
            local.update { it.copy(loading = true, loadError = null) }
            viewModelScope.launch {
                var detail: MaintenanceDetail? = null
                val error = runCatchingKuma { detail = repository.get(maintenanceId) }
                local.update { it.copy(loading = false, detail = detail ?: it.detail, loadError = error) }
            }
        }

        fun pause() = run(MaintenanceAction.PAUSE) { repository.pause(maintenanceId) }

        fun resume() = run(MaintenanceAction.RESUME) { repository.resume(maintenanceId) }

        fun delete() = run(MaintenanceAction.DELETE) { repository.delete(maintenanceId) }

        fun consumeActionResult() {
            local.update { it.copy(actionResult = null) }
        }

        private fun run(
            action: MaintenanceAction,
            block: suspend () -> Unit,
        ) {
            if (local.value.actionInProgress != null) return
            local.update { it.copy(actionInProgress = action, actionResult = null) }
            viewModelScope.launch {
                val error = runCatchingKuma(block)
                local.update {
                    it.copy(
                        actionInProgress = null,
                        actionResult = error?.let(ActionResult::Failed) ?: ActionResult.Done(action),
                        deleted = it.deleted || (action == MaintenanceAction.DELETE && error == null),
                    )
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
