package net.kirkanos.uptimekuma.feature.monitors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import javax.inject.Inject

@HiltViewModel
class MonitorsViewModel
    @Inject
    constructor(
        serverInfoRepository: ServerInfoRepository,
    ) : ViewModel() {
        /** Version string of the connected server, or `null` while disconnected. */
        val serverVersion: StateFlow<String?> =
            serverInfoRepository
                .observeServerInfo()
                .map { it?.version }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = null,
                )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
