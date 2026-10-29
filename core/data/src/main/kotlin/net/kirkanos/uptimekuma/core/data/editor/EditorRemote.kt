package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.kumaclient.callOk
import javax.inject.Inject

/** The two things the editor repositories need from the connection: acked calls and server-pushed lists. */
internal interface EditorRemote {
    /** Emits [event] with [args] and returns the `{ok:true,...}` ack; throws KumaException otherwise. */
    suspend fun call(
        event: String,
        vararg args: Any?,
    ): JsonObject

    /** Raw args of the latest list event (e.g. `proxyList`), `null` until received. */
    fun snapshot(event: String): Flow<List<JsonElement>?>
}

internal class ManagerEditorRemote
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
    ) : EditorRemote {
        override suspend fun call(
            event: String,
            vararg args: Any?,
        ): JsonObject = manager.withApi { api -> api.callOk(event, *args) }

        override fun snapshot(event: String): Flow<List<JsonElement>?> = manager.snapshot(event)
    }
