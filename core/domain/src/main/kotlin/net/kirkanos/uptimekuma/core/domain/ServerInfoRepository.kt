package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.ServerInfo

/** Read access to the connected server's metadata. Implemented in `:core:data`. */
interface ServerInfoRepository {
    /** Emits the latest known server info, or `null` while no server is connected. */
    fun observeServerInfo(): Flow<ServerInfo?>
}
