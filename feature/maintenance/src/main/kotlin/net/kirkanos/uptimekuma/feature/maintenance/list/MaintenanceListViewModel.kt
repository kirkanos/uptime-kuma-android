package net.kirkanos.uptimekuma.feature.maintenance.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import javax.inject.Inject

/** Sections of the list, in display order. */
enum class MaintenanceGroup(
    val statuses: Set<MaintenanceStatus>,
) {
    ACTIVE(setOf(MaintenanceStatus.UNDER_MAINTENANCE)),
    SCHEDULED(setOf(MaintenanceStatus.SCHEDULED)),
    PAUSED(setOf(MaintenanceStatus.INACTIVE)),
    ENDED(setOf(MaintenanceStatus.ENDED)),
    UNKNOWN(setOf(MaintenanceStatus.UNKNOWN)),
    ;

    companion object {
        fun of(status: MaintenanceStatus): MaintenanceGroup = entries.first { status in it.statuses }

        /** Non-empty groups in display order; items keep the repository order (title within status). */
        fun group(list: List<Maintenance>): List<Pair<MaintenanceGroup, List<Maintenance>>> {
            val byGroup = list.groupBy { of(it.status) }
            return entries.mapNotNull { group -> byGroup[group]?.let { group to it } }
        }
    }
}

enum class MaintenanceAction { PAUSE, RESUME, DELETE }

/** Feedback after an action, shown once as a snackbar. */
sealed interface ActionResult {
    data class Done(
        val action: MaintenanceAction,
    ) : ActionResult

    data class Failed(
        val error: KumaError,
    ) : ActionResult
}

data class MaintenanceListUiState(
    /** `null` until the server sent `maintenanceList`. */
    val groups: List<Pair<MaintenanceGroup, List<Maintenance>>>? = null,
    val refreshing: Boolean = false,
    /** Error of the initial refresh, only relevant while there is no data. */
    val loadError: KumaError? = null,
    val busyIds: Set<Long> = emptySet(),
    val actionResult: ActionResult? = null,
) {
    val loading: Boolean get() = groups == null && loadError == null
    val isEmpty: Boolean get() = groups?.isEmpty() == true
}

private data class ListLocal(
    val refreshing: Boolean = false,
    val loadError: KumaError? = null,
    val busyIds: Set<Long> = emptySet(),
    val actionResult: ActionResult? = null,
)

@HiltViewModel
class MaintenanceListViewModel
    @Inject
    constructor(
        private val repository: MaintenanceRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(ListLocal())

        val state: StateFlow<MaintenanceListUiState> =
            combine(repository.observeMaintenances(), local) { list, local ->
                MaintenanceListUiState(
                    groups = list?.let(MaintenanceGroup::group),
                    refreshing = local.refreshing,
                    loadError = local.loadError.takeIf { list == null },
                    busyIds = local.busyIds,
                    actionResult = local.actionResult,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MaintenanceListUiState())

        init {
            refresh()
        }

        /** Asks the server to push the list again (also makes sure a connection exists). */
        fun refresh() {
            if (local.value.refreshing) return
            local.update { it.copy(refreshing = true, loadError = null) }
            viewModelScope.launch {
                val error = runCatchingKuma { repository.refresh() }
                local.update { it.copy(refreshing = false, loadError = error) }
            }
        }

        fun perform(
            action: MaintenanceAction,
            id: Long,
        ) = when (action) {
            MaintenanceAction.PAUSE -> pause(id)
            MaintenanceAction.RESUME -> resume(id)
            MaintenanceAction.DELETE -> delete(id)
        }

        fun pause(id: Long) = run(id, MaintenanceAction.PAUSE) { repository.pause(id) }

        fun resume(id: Long) = run(id, MaintenanceAction.RESUME) { repository.resume(id) }

        fun delete(id: Long) = run(id, MaintenanceAction.DELETE) { repository.delete(id) }

        fun consumeActionResult() {
            local.update { it.copy(actionResult = null) }
        }

        private fun run(
            id: Long,
            action: MaintenanceAction,
            block: suspend () -> Unit,
        ) {
            if (id in local.value.busyIds) return
            local.update { it.copy(busyIds = it.busyIds + id) }
            viewModelScope.launch {
                val error = runCatchingKuma(block)
                local.update {
                    it.copy(
                        busyIds = it.busyIds - id,
                        actionResult = error?.let(ActionResult::Failed) ?: ActionResult.Done(action),
                    )
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Runs [block]; returns the error instead of throwing (cancellation still propagates). */
internal suspend fun runCatchingKuma(block: suspend () -> Unit): KumaError? =
    try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (e as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, e.message)
    }
