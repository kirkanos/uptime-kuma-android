package net.kirkanos.uptimekuma.feature.statuspage.publicview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.feature.statuspage.PublicStatusPageRoute
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageLoader
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageViewState
import javax.inject.Inject

data class PublicStatusPageUiState(
    /** `null` when the link could not be parsed. */
    val address: StatusPageAddress?,
    val view: StatusPageViewState = StatusPageViewState(loading = address != null),
) {
    val invalidUrl: Boolean get() = address == null
}

/** Read-only status page from any public link, over REST only (works without login). */
@HiltViewModel
class PublicStatusPageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        repository: StatusPageRepository,
    ) : ViewModel() {
        val address: StatusPageAddress? =
            savedStateHandle.get<String>(PublicStatusPageRoute.ARG_URL)?.let(StatusPageAddress::parse)

        private val loader: StatusPageLoader? =
            address?.let { address ->
                StatusPageLoader(
                    scope = viewModelScope,
                    loadPage = { repository.loadPublic(address.slug, address) },
                    loadHeartbeats = { repository.loadHeartbeats(address.slug, address) },
                    loadHistory = { cursor -> repository.loadPublicIncidentHistory(address, cursor) },
                )
            }

        val state: StateFlow<PublicStatusPageUiState> =
            (loader?.state ?: MutableStateFlow(StatusPageViewState(loading = false)))
                .map { PublicStatusPageUiState(address, it) }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    PublicStatusPageUiState(address),
                )

        init {
            loader?.load()
        }

        fun refresh() {
            loader?.refresh()
        }

        fun refreshHeartbeats() {
            loader?.refreshHeartbeats()
        }

        fun loadMoreHistory() {
            loader?.loadMoreHistory()
        }

        fun retryHistory() {
            loader?.reloadHistory()
        }

        fun dismissRefreshError() {
            loader?.dismissRefreshError()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
