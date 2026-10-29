package net.kirkanos.uptimekuma.feature.statuspage.list

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
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaError
import net.kirkanos.uptimekuma.feature.statuspage.view.kumaResult
import javax.inject.Inject

/** State of the "new status page" dialog. */
data class CreateState(
    val title: String = "",
    val slug: String = "",
    /** False until the user typed into the slug field; until then the slug follows the title. */
    val slugEdited: Boolean = false,
    val inProgress: Boolean = false,
    val error: KumaError? = null,
    /** Set after success; the screen navigates to the editor once. */
    val createdSlug: String? = null,
) {
    val slugProblem: StatusPageSlug.Problem? get() = StatusPageSlug.validate(slug)
    val canSubmit: Boolean get() = title.isNotBlank() && slugProblem == null && !inProgress
}

data class StatusPageListUiState(
    /** `null` while the list was not received yet. */
    val pages: List<StatusPageConfig>? = null,
    val loadError: KumaError? = null,
    val refreshing: Boolean = false,
    val baseUrl: String? = null,
    /** Non-null while the create dialog is open. */
    val create: CreateState? = null,
) {
    val loading: Boolean get() = pages == null && loadError == null

    /** Prefill for the public viewer: the own server's status URL prefix. */
    val publicUrlPrefill: String get() = baseUrl?.let { "${it.trimEnd('/')}/status/" }.orEmpty()

    fun webUrl(slug: String): String? = baseUrl?.let { StatusPageAddress(it, slug).webUrl }
}

private data class LocalState(
    val loadError: KumaError? = null,
    val refreshing: Boolean = false,
    val create: CreateState? = null,
)

@HiltViewModel
class StatusPageListViewModel
    @Inject
    constructor(
        private val repository: StatusPageRepository,
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(LocalState())

        val state: StateFlow<StatusPageListUiState> =
            combine(
                repository.observeStatusPages(),
                sessionRepository.observeServerConfig(),
                local,
            ) { pages, server, local ->
                StatusPageListUiState(
                    pages = pages,
                    loadError = local.loadError.takeIf { pages == null },
                    refreshing = local.refreshing,
                    baseUrl = server?.baseUrl,
                    create = local.create,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusPageListUiState())

        init {
            refresh()
        }

        /** Makes sure the socket is up so the server pushes `statusPageList`. */
        fun refresh() {
            if (local.value.refreshing) return
            local.update { it.copy(refreshing = true, loadError = null) }
            viewModelScope.launch {
                val result = kumaResult { repository.refreshList() }
                local.update { it.copy(refreshing = false, loadError = result.kumaError()) }
            }
        }

        fun openCreate() {
            local.update { it.copy(create = CreateState()) }
        }

        fun dismissCreate() {
            local.update { if (it.create?.inProgress == true) it else it.copy(create = null) }
        }

        fun onCreateTitle(title: String) {
            updateCreate {
                it.copy(
                    title = title,
                    slug = if (it.slugEdited) it.slug else StatusPageSlug.fromTitle(title),
                )
            }
        }

        fun onCreateSlug(slug: String) {
            updateCreate { it.copy(slug = slug.lowercase().replace(' ', '-'), slugEdited = true) }
        }

        fun submitCreate() {
            val create = local.value.create ?: return
            if (!create.canSubmit) return
            updateCreate { it.copy(inProgress = true, error = null) }
            viewModelScope.launch {
                val result = kumaResult { repository.create(create.title.trim(), create.slug) }
                updateCreate {
                    it.copy(inProgress = false, error = result.kumaError(), createdSlug = result.getOrNull())
                }
            }
        }

        /** Called after navigating to the editor of the new page. */
        fun consumeCreated() {
            local.update { it.copy(create = null) }
        }

        private fun updateCreate(change: (CreateState) -> CreateState) {
            local.update { state -> state.create?.let { state.copy(create = change(it)) } ?: state }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
