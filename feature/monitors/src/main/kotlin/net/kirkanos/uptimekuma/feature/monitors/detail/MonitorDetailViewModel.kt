package net.kirkanos.uptimekuma.feature.monitors.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.feature.monitors.MonitorDetailRoute
import javax.inject.Inject

enum class ChartPeriod(
    val hours: Int,
) {
    H3(3),
    H6(6),
    H24(24),
    W1(168),
}

data class ChartState(
    val period: ChartPeriod = ChartPeriod.H24,
    val loading: Boolean = true,
    val points: List<ChartPoint> = emptyList(),
    val error: KumaError? = null,
)

data class EventsState(
    val items: List<Heartbeat> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: KumaError? = null,
)

enum class MonitorAction { PAUSE, RESUME, DELETE }

data class MonitorDetailUiState(
    val loading: Boolean = true,
    val overview: MonitorOverview? = null,
    /** Server base URL for "open in web UI". */
    val baseUrl: String? = null,
    val chart: ChartState = ChartState(),
    val events: EventsState = EventsState(),
    val actionInProgress: MonitorAction? = null,
    /** Transient error of the last action, shown once. */
    val actionError: KumaError? = null,
    /** Set after a successful delete; the screen navigates back. */
    val deleted: Boolean = false,
) {
    val notFound: Boolean get() = !loading && overview == null && !deleted

    val webUrl: String?
        get() = baseUrl?.let { base -> overview?.let { "${base.trimEnd('/')}/dashboard/${it.monitor.id}" } }
}

private data class LocalState(
    val chart: ChartState = ChartState(),
    val events: EventsState = EventsState(),
    val actionInProgress: MonitorAction? = null,
    val actionError: KumaError? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class MonitorDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val monitorRepository: MonitorRepository,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        val monitorId: Long = checkNotNull(savedStateHandle.get<Long>(MonitorDetailRoute.ARG_MONITOR_ID))

        private val local = MutableStateFlow(LocalState())
        private var chartJob: Job? = null

        val state: StateFlow<MonitorDetailUiState> =
            combine(
                monitorRepository.observeMonitor(monitorId).onStart<MonitorOverview?> { emit(LOADING) },
                sessionRepository.observeServerConfig(),
                local,
            ) { overview, config, local ->
                MonitorDetailUiState(
                    loading = overview === LOADING,
                    overview = overview.takeUnless { it === LOADING },
                    baseUrl = config?.baseUrl,
                    chart = local.chart,
                    events = local.events,
                    actionInProgress = local.actionInProgress,
                    actionError = local.actionError,
                    deleted = local.deleted,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MonitorDetailUiState())

        init {
            loadChart(ChartPeriod.H24)
            loadMoreEvents()
        }

        fun selectPeriod(period: ChartPeriod) {
            if (period == local.value.chart.period && !local.value.chart.loading && local.value.chart.error == null) {
                return
            }
            loadChart(period)
        }

        fun retryChart() = loadChart(local.value.chart.period)

        private fun loadChart(period: ChartPeriod) {
            chartJob?.cancel()
            local.update { it.copy(chart = ChartState(period = period, loading = true)) }
            chartJob =
                viewModelScope.launch {
                    val result = runCatchingKuma { monitorRepository.loadChart(monitorId, period.hours) }
                    local.update {
                        it.copy(
                            chart =
                                ChartState(
                                    period = period,
                                    loading = false,
                                    points = result.getOrNull().orEmpty(),
                                    error = result.errorOrNull(),
                                ),
                        )
                    }
                }
        }

        /** Loads the next page of important events; no-op while loading or at the end. */
        fun loadMoreEvents() {
            val events = local.value.events
            if (events.loading || events.endReached) return
            local.update { it.copy(events = it.events.copy(loading = true, error = null)) }
            viewModelScope.launch {
                val result =
                    runCatchingKuma { monitorRepository.loadImportantEvents(monitorId, events.items.size, PAGE_SIZE) }
                local.update { state ->
                    val page = result.getOrNull()
                    state.copy(
                        events =
                            state.events.copy(
                                items = if (page == null) state.events.items else state.events.items + page,
                                loading = false,
                                endReached = page != null && page.size < PAGE_SIZE,
                                error = result.errorOrNull(),
                            ),
                    )
                }
            }
        }

        fun pause() = runAction(MonitorAction.PAUSE) { monitorRepository.pause(monitorId) }

        fun resume() = runAction(MonitorAction.RESUME) { monitorRepository.resume(monitorId) }

        fun delete() = runAction(MonitorAction.DELETE) { monitorRepository.delete(monitorId) }

        fun dismissActionError() {
            local.update { it.copy(actionError = null) }
        }

        private fun runAction(
            action: MonitorAction,
            block: suspend () -> Unit,
        ) {
            if (local.value.actionInProgress != null) return
            local.update { it.copy(actionInProgress = action, actionError = null) }
            viewModelScope.launch {
                val result = runCatchingKuma { block() }
                local.update {
                    it.copy(
                        actionInProgress = null,
                        actionError = result.errorOrNull(),
                        deleted = it.deleted || (action == MonitorAction.DELETE && result.isSuccess),
                    )
                }
            }
        }

        private suspend fun <T> runCatchingKuma(block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

        private fun Result<*>.errorOrNull(): KumaError? =
            exceptionOrNull()?.let { (it as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, it.message) }

        companion object {
            const val PAGE_SIZE = 25
            private const val STOP_TIMEOUT_MS = 5_000L

            /** Sentinel for "not loaded yet" (distinct from `null` = monitor does not exist). */
            private val LOADING =
                MonitorOverview(
                    monitor = Monitor(id = -1, name = "", type = ""),
                    status = null,
                )
        }
    }
