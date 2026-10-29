package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonElement
import net.kirkanos.uptimekuma.core.domain.CloudflaredRepository
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CloudflaredRepository] over `cloudflared_*` (`server/socket-handlers/cloudflared-socket-handler.js`). The state
 * arrives as separate events (installed/running/token/message/errorMessage) after `cloudflared_join` and whenever
 * the tunnel changes; none of the commands is acknowledged on success.
 */
@Singleton
internal class KumaCloudflaredRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : CloudflaredRepository {
        override fun observeState(): Flow<CloudflaredState> =
            combine(
                backend.snapshot("cloudflared_installed"),
                backend.snapshot("cloudflared_running"),
                backend.snapshot("cloudflared_token"),
                backend.snapshot("cloudflared_message"),
                backend.snapshot("cloudflared_errorMessage"),
            ) { installed, running, token, message, error ->
                CloudflaredState(
                    installed = installed.first().asLenientBoolean(),
                    running = running.first().asLenientBoolean() == true,
                    token = token.first().asStringOrNull()?.takeIf { it.isNotBlank() },
                    message = message.first().asStringOrNull()?.takeIf { it.isNotBlank() },
                    errorMessage = error.first().asStringOrNull()?.takeIf { it.isNotBlank() },
                )
            }.distinctUntilChanged()

        override suspend fun join() {
            backend.emitUnacknowledged("cloudflared_join")
        }

        override suspend fun leave() {
            backend.emitUnacknowledged("cloudflared_leave")
        }

        override suspend fun start(token: String) {
            backend.emitUnacknowledged("cloudflared_start", token.trim())
        }

        override suspend fun stop(currentPassword: String?) {
            backend.emitUnacknowledged("cloudflared_stop", currentPassword)
        }

        override suspend fun removeToken() {
            backend.emitUnacknowledged("cloudflared_removeToken")
        }

        private fun List<JsonElement>?.first(): JsonElement? = this?.firstOrNull()
    }
