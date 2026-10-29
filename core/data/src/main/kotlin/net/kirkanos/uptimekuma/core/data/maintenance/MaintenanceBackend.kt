package net.kirkanos.uptimekuma.core.data.maintenance

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.kumaclient.callOk
import javax.inject.Inject

/** The two things the maintenance repository needs from the connection; a seam for unit tests. */
internal interface MaintenanceBackend {
    /** Raw args of a server-pushed list event, `null` until received. */
    fun snapshot(event: String): Flow<List<JsonElement>?>

    /** Emits [event] and returns the ack, throwing `KumaException` for `ok:false`. */
    suspend fun call(
        event: String,
        vararg args: Any?,
    ): JsonObject
}

internal class ConnectionMaintenanceBackend
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
    ) : MaintenanceBackend {
        override fun snapshot(event: String): Flow<List<JsonElement>?> = manager.snapshot(event)

        override suspend fun call(
            event: String,
            vararg args: Any?,
        ): JsonObject = manager.withApi { api -> api.callOk(event, *args) }
    }
