package net.kirkanos.uptimekuma.feature.statuspage.view

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats

/** Incident history list with cursor paging. */
data class HistoryState(
    val items: List<Incident> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val cursor: String? = null,
    val error: KumaError? = null,
)

/** Everything the rendered status page needs. */
data class StatusPageViewState(
    val loading: Boolean = true,
    val page: PublicStatusPage? = null,
    val heartbeats: StatusPageHeartbeats = StatusPageHeartbeats(),
    /** Error of the initial load (nothing to show). */
    val error: KumaError? = null,
    val refreshing: Boolean = false,
    /** Error of a later refresh; the old data stays visible. */
    val refreshError: KumaError? = null,
    val history: HistoryState = HistoryState(),
)

/**
 * Loads a status page (public data + heartbeats + incident history) into [state]. Shared by the admin
 * detail screen and the public viewer, which differ only in the data sources passed in.
 */
class StatusPageLoader(
    private val scope: CoroutineScope,
    private val loadPage: suspend () -> PublicStatusPage,
    private val loadHeartbeats: suspend () -> StatusPageHeartbeats,
    private val loadHistory: suspend (cursor: String?) -> IncidentHistoryPage,
) {
    private val mutableState = MutableStateFlow(StatusPageViewState())
    val state: StateFlow<StatusPageViewState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var historyJob: Job? = null

    /** Initial load; also used to retry after an error. */
    fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(loading = it.page == null, refreshing = it.page != null, error = null) }
        loadJob =
            scope.launch {
                val page = async { kumaResult { loadPage() } }
                val beats = async { kumaResult { loadHeartbeats() } }
                val pageResult = page.await()
                val beatsResult = beats.await()
                mutableState.update { state ->
                    val loaded = pageResult.getOrNull()
                    when {
                        loaded != null -> {
                            state.copy(
                                loading = false,
                                refreshing = false,
                                page = loaded,
                                heartbeats = beatsResult.getOrNull() ?: state.heartbeats,
                                error = null,
                                refreshError = beatsResult.kumaError(),
                            )
                        }

                        state.page != null -> {
                            state.copy(refreshing = false, refreshError = pageResult.kumaError())
                        }

                        else -> {
                            state.copy(loading = false, refreshing = false, error = pageResult.kumaError())
                        }
                    }
                }
            }
        reloadHistory()
    }

    /** Pull-to-refresh / refresh action. */
    fun refresh() = load()

    /** Periodic heartbeat refresh (auto refresh interval). */
    fun refreshHeartbeats() {
        if (mutableState.value.page == null) return
        scope.launch {
            kumaResult { loadHeartbeats() }.getOrNull()?.let { beats ->
                mutableState.update { it.copy(heartbeats = beats) }
            }
        }
    }

    fun dismissRefreshError() {
        mutableState.update { it.copy(refreshError = null) }
    }

    /** Replaces the pinned incident locally after posting/unpinning. */
    fun setPinnedIncident(incident: Incident?) {
        mutableState.update { state ->
            state.copy(page = state.page?.copy(incidents = listOfNotNull(incident)))
        }
    }

    fun reloadHistory() {
        historyJob?.cancel()
        mutableState.update { it.copy(history = HistoryState(loading = true)) }
        historyJob = scope.launch { fetchHistory(null) }
    }

    fun loadMoreHistory() {
        val history = mutableState.value.history
        if (history.loading || !history.hasMore) return
        mutableState.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        historyJob = scope.launch { fetchHistory(history.cursor) }
    }

    private suspend fun fetchHistory(cursor: String?) {
        val result = kumaResult { loadHistory(cursor) }
        mutableState.update { state ->
            val page = result.getOrNull()
            val old = if (cursor == null) emptyList() else state.history.items
            state.copy(
                history =
                    HistoryState(
                        items = if (page == null) old else old + page.incidents,
                        loading = false,
                        loaded = true,
                        hasMore = page?.hasMore ?: state.history.hasMore,
                        cursor = page?.nextCursor ?: state.history.cursor,
                        error = result.kumaError(),
                    ),
            )
        }
    }
}

/** Runs [block], turning exceptions (except cancellation) into a failed [Result]. */
internal suspend fun <T> kumaResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

internal fun Result<*>.kumaError(): KumaError? =
    exceptionOrNull()?.let { (it as? KumaException)?.error ?: KumaError(KumaError.Kind.UNKNOWN, it.message) }
