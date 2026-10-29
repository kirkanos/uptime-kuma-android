package net.kirkanos.uptimekuma.feature.statuspage.detail

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
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.feature.statuspage.StatusPageDetailRoute
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageLoader
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageViewState
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaError
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaResult
import javax.inject.Inject

enum class StatusPageAction { POST_INCIDENT, UNPIN_INCIDENT, DELETE }

data class StatusPageDetailUiState(
    val slug: String,
    val view: StatusPageViewState = StatusPageViewState(),
    /** Admin config from `statusPageList` (published flag); `null` while unknown. */
    val config: StatusPageConfig? = null,
    val baseUrl: String? = null,
    val actionInProgress: StatusPageAction? = null,
    val actionError: KumaError? = null,
    /** Incremented after a successful post, so the editor can close. */
    val incidentPosted: Int = 0,
    val deleted: Boolean = false,
) {
    /** `<base>/status/<slug>` for "Open in browser". */
    val webUrl: String? get() = baseUrl?.let { StatusPageAddress(it, slug).webUrl }
}

private data class AdminState(
    val actionInProgress: StatusPageAction? = null,
    val actionError: KumaError? = null,
    val incidentPosted: Int = 0,
    val deleted: Boolean = false,
)

@HiltViewModel
class StatusPageDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: StatusPageRepository,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        val slug: String = checkNotNull(savedStateHandle.get<String>(StatusPageDetailRoute.ARG_SLUG))

        private val loader =
            StatusPageLoader(
                scope = viewModelScope,
                loadPage = { repository.loadPublic(slug) },
                loadHeartbeats = { repository.loadHeartbeats(slug) },
                loadHistory = { cursor -> repository.loadIncidentHistory(slug, cursor) },
            )
        private val admin = MutableStateFlow(AdminState())

        val state: StateFlow<StatusPageDetailUiState> =
            combine(
                loader.state,
                repository.observeStatusPages(),
                sessionRepository.observeServerConfig(),
                admin,
            ) { view, pages, server, admin ->
                StatusPageDetailUiState(
                    slug = slug,
                    view = view,
                    config = pages?.firstOrNull { it.slug == slug },
                    baseUrl = server?.baseUrl,
                    actionInProgress = admin.actionInProgress,
                    actionError = admin.actionError,
                    incidentPosted = admin.incidentPosted,
                    deleted = admin.deleted,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusPageDetailUiState(slug))

        init {
            loader.load()
        }

        fun refresh() = loader.refresh()

        fun refreshHeartbeats() = loader.refreshHeartbeats()

        fun loadMoreHistory() = loader.loadMoreHistory()

        fun retryHistory() = loader.reloadHistory()

        fun dismissRefreshError() = loader.dismissRefreshError()

        fun postIncident(draft: IncidentDraft) =
            runAction(StatusPageAction.POST_INCIDENT) {
                val incident = repository.postIncident(slug, draft)
                loader.setPinnedIncident(incident)
                loader.reloadHistory()
                admin.update { it.copy(incidentPosted = it.incidentPosted + 1) }
            }

        fun unpinIncident() =
            runAction(StatusPageAction.UNPIN_INCIDENT) {
                repository.unpinIncident(slug)
                loader.setPinnedIncident(null)
                loader.reloadHistory()
            }

        fun delete() =
            runAction(StatusPageAction.DELETE) {
                repository.delete(slug)
                admin.update { it.copy(deleted = true) }
            }

        fun dismissActionError() {
            admin.update { it.copy(actionError = null) }
        }

        private fun runAction(
            action: StatusPageAction,
            block: suspend () -> Unit,
        ) {
            if (admin.value.actionInProgress != null) return
            admin.update { it.copy(actionInProgress = action, actionError = null) }
            viewModelScope.launch {
                val result = kumaResult { block() }
                admin.update { it.copy(actionInProgress = null, actionError = result.kumaError()) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
