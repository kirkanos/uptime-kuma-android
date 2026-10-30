package net.kirkanos.uptimekuma.feature.monitors.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.AppUpdateRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.AppUpdate
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorTag
import javax.inject.Inject

data class DashboardUiState(
    val loading: Boolean = true,
    val rows: List<MonitorRow> = emptyList(),
    val counts: StatusCounts = StatusCounts(),
    val tags: List<MonitorTag> = emptyList(),
    val filter: DashboardFilter = DashboardFilter(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val refreshing: Boolean = false,
    /** Transient error of the last manual refresh, shown once as a snackbar. */
    val refreshError: KumaError? = null,
    /** Newer app release the user has not dismissed yet. */
    val appUpdate: AppUpdate? = null,
)

private data class RefreshState(
    val refreshing: Boolean = false,
    val error: KumaError? = null,
)

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        monitorRepository: MonitorRepository,
        private val sessionRepository: SessionRepository,
        private val appUpdates: AppUpdateRepository,
    ) : ViewModel() {
        private val filter = MutableStateFlow(DashboardFilter())
        private val refresh = MutableStateFlow(RefreshState())

        val state: StateFlow<DashboardUiState> =
            combine(
                monitorRepository.observeMonitors().onStart<List<MonitorOverview>?> { emit(null) },
                filter,
                refresh,
                sessionRepository.observeConnectionState(),
                appUpdates.observeUpdate(),
            ) { monitors, filter, refresh, connection, update ->
                DashboardUiState(
                    loading = monitors == null,
                    rows = monitors?.let { MonitorList.rows(it, filter) }.orEmpty(),
                    counts = monitors?.let { MonitorList.counts(it) } ?: StatusCounts(),
                    tags = monitors?.let { MonitorList.tags(it) }.orEmpty(),
                    filter = filter,
                    connection = connection,
                    refreshing = refresh.refreshing,
                    refreshError = refresh.error,
                    appUpdate = update?.takeUnless { it.dismissed },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

        init {
            // Throttled to one request a day by the repository.
            viewModelScope.launch { appUpdates.check() }
        }

        fun dismissAppUpdate(version: String) {
            viewModelScope.launch { appUpdates.dismiss(version) }
        }

        fun onQueryChange(query: String) {
            filter.update { it.copy(query = query) }
        }

        fun toggleStatus(status: DisplayStatus) {
            filter.update { it.copy(statuses = it.statuses.toggle(status)) }
        }

        fun toggleTag(tagId: Long) {
            filter.update { it.copy(tagIds = it.tagIds.toggle(tagId)) }
        }

        fun toggleProblemsFirst() {
            filter.update { it.copy(problemsFirst = !it.problemsFirst) }
        }

        fun toggleGroup(groupId: Long) {
            filter.update { it.copy(collapsedGroups = it.collapsedGroups.toggle(groupId)) }
        }

        fun clearFilters() {
            filter.update { it.copy(query = "", statuses = emptySet(), tagIds = emptySet()) }
        }

        /** Pull-to-refresh / retry: one sync round trip through the session. */
        fun refresh() {
            if (refresh.value.refreshing) return
            refresh.value = RefreshState(refreshing = true)
            viewModelScope.launch {
                val error =
                    try {
                        if (sessionRepository.syncOnce()) null else KumaError(KumaError.Kind.UNAUTHORIZED)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: KumaException) {
                        e.error
                    } catch (e: Exception) {
                        KumaError(KumaError.Kind.UNKNOWN, e.message)
                    }
                refresh.value = RefreshState(refreshing = false, error = error)
            }
        }

        fun dismissRefreshError() {
            refresh.update { it.copy(error = null) }
        }

        private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
