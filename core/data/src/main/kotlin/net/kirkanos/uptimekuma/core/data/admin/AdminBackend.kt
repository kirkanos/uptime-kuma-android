package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.secret.SecretStore
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.callOk
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import javax.inject.Inject

/** What the server administration repositories need from the connection; a seam for unit tests. */
internal interface AdminBackend {
    /** Raw args of a server-pushed event from the snapshot set, `null` until received. */
    fun snapshot(event: String): Flow<List<JsonElement>?>

    /** Emits [event] and returns the ack, throwing `KumaException` for `ok:false`. */
    suspend fun call(
        event: String,
        vararg args: Any?,
        timeoutMillis: Long = KumaApi.DEFAULT_TIMEOUT_MILLIS,
    ): JsonObject

    /**
     * Emits an event that Kuma does not acknowledge on success (`cloudflared_*`). Waits [graceMillis] for an
     * error ack (`{ok:false}`) and throws it; silence means success.
     */
    suspend fun emitUnacknowledged(
        event: String,
        vararg args: Any?,
        graceMillis: Long = UNACKED_GRACE_MILLIS,
    )

    /** Replaces the stored session token (after `changePassword`, which invalidates the old one). */
    suspend fun storeSessionToken(token: String)

    companion object {
        const val UNACKED_GRACE_MILLIS = 1_500L
    }
}

internal class ConnectionAdminBackend
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
        private val secretStore: SecretStore,
    ) : AdminBackend {
        override fun snapshot(event: String): Flow<List<JsonElement>?> = manager.snapshot(event)

        override suspend fun call(
            event: String,
            vararg args: Any?,
            timeoutMillis: Long,
        ): JsonObject = manager.withApi { api -> api.callOk(event, *args, timeoutMillis = timeoutMillis) }

        override suspend fun emitUnacknowledged(
            event: String,
            vararg args: Any?,
            graceMillis: Long,
        ) {
            manager.withApi { api ->
                try {
                    api.callOk(event, *args, timeoutMillis = graceMillis)
                } catch (e: KumaException) {
                    if (e.error.kind != KumaError.Kind.TIMEOUT) throw e
                }
            }
        }

        override suspend fun storeSessionToken(token: String) {
            secretStore.put(KumaConnectionManager.TOKEN_KEY, token)
        }
    }
