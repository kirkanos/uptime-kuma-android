package net.kirkanos.uptimekuma.feature.settings.server.proxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.CloudflaredRepository
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.common.runKuma
import net.kirkanos.uptimekuma.feature.settings.server.common.toKumaError
import javax.inject.Inject

data class CloudflaredUiState(
    val tunnel: CloudflaredState = CloudflaredState(),
    /** Token being edited; prefilled with the stored one. */
    val tokenInput: String = "",
    val busy: Boolean = false,
    val message: AdminMessage? = null,
) {
    val canStart: Boolean get() = tunnel.installed == true && !tunnel.running && !busy && tokenInput.isNotBlank()
}

private data class TunnelLocal(
    val tokenInput: String? = null,
    val busy: Boolean = false,
    val message: AdminMessage? = null,
)

/** Cloudflare tunnel card of the Reverse proxy screen. Joins the server's cloudflared room while alive. */
@HiltViewModel
class CloudflaredViewModel
    @Inject
    constructor(
        private val repository: CloudflaredRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(TunnelLocal())

        val state: StateFlow<CloudflaredUiState> =
            combine(repository.observeState(), local) { tunnel, local ->
                CloudflaredUiState(
                    tunnel = tunnel,
                    tokenInput = local.tokenInput ?: tunnel.token.orEmpty(),
                    busy = local.busy,
                    message = local.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CloudflaredUiState())

        init {
            viewModelScope.launch {
                runKuma { repository.join() }.onFailure { e ->
                    local.update { it.copy(message = AdminMessage.Failed(e.toKumaError())) }
                }
            }
        }

        fun setToken(token: String) {
            local.update { it.copy(tokenInput = token) }
        }

        fun start() {
            val token = (local.value.tokenInput ?: state.value.tunnel.token).orEmpty().trim()
            if (token.isEmpty()) return
            run(AdminDone.TUNNEL_STARTING) { repository.start(token) }
        }

        /** [currentPassword] is `null` when the server has authentication disabled. */
        fun stop(currentPassword: String?) = run(AdminDone.TUNNEL_STOPPING) { repository.stop(currentPassword) }

        fun removeToken() =
            run(AdminDone.TOKEN_REMOVED) {
                repository.removeToken()
                local.update { it.copy(tokenInput = "") }
            }

        fun consumeMessage() {
            local.update { it.copy(message = null) }
        }

        private fun run(
            done: AdminDone,
            block: suspend () -> Unit,
        ) {
            if (local.value.busy) return
            local.update { it.copy(busy = true) }
            viewModelScope.launch {
                val message =
                    runKuma(
                        block,
                    ).fold({ AdminMessage.Done(done) }, { AdminMessage.Failed(it.toKumaError()) })
                local.update { it.copy(busy = false, message = message) }
            }
        }

        override fun onCleared() {
            // Leaving the room is best effort; it must outlive the (already cancelled) viewModelScope.
            viewModelScope.launch(NonCancellable) { runKuma { repository.leave() } }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
