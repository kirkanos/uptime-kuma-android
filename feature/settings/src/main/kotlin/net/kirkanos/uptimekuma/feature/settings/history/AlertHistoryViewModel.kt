package net.kirkanos.uptimekuma.feature.settings.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.AlertHistoryRepository
import net.kirkanos.uptimekuma.core.model.AlertRecord
import javax.inject.Inject

sealed interface AlertHistoryUiState {
    data object Loading : AlertHistoryUiState

    data class Loaded(
        /** Newest first. */
        val alerts: List<AlertRecord>,
    ) : AlertHistoryUiState
}

@HiltViewModel
class AlertHistoryViewModel
    @Inject
    constructor(
        private val repository: AlertHistoryRepository,
    ) : ViewModel() {
        val state: StateFlow<AlertHistoryUiState> =
            repository
                .observeAlerts()
                .map { AlertHistoryUiState.Loaded(it.sortedByDescending(AlertRecord::receivedAtMillis)) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlertHistoryUiState.Loading)

        fun clear() {
            viewModelScope.launch { repository.clear() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
