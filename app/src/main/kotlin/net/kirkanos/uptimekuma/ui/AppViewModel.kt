package net.kirkanos.uptimekuma.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import javax.inject.Inject

/** Whether a server is configured; [Loading] until the first value is read (splash screen stays up). */
sealed interface SessionGate {
    data object Loading : SessionGate

    data class Ready(
        val configured: Boolean,
    ) : SessionGate
}

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        sessionRepository: SessionRepository,
    ) : ViewModel() {
        val gate: StateFlow<SessionGate> =
            sessionRepository
                .observeServerConfig()
                .map { SessionGate.Ready(configured = it != null) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, SessionGate.Loading)

        private val pendingMonitor = MutableStateFlow<Long?>(null)

        /** Monitor to open from a notification deep link, consumed by the nav host. */
        val pendingMonitorId: StateFlow<Long?> = pendingMonitor.asStateFlow()

        fun openMonitor(id: Long) {
            pendingMonitor.value = id
        }

        fun consumePendingMonitor() {
            pendingMonitor.value = null
        }
    }
