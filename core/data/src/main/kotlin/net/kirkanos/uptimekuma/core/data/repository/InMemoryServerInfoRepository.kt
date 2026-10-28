package net.kirkanos.uptimekuma.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.model.ServerInfo
import javax.inject.Inject
import javax.inject.Singleton

/** Placeholder implementation until the socket-backed repository lands. */
@Singleton
class InMemoryServerInfoRepository
    @Inject
    constructor() : ServerInfoRepository {
        private val serverInfo = MutableStateFlow<ServerInfo?>(null)

        override fun observeServerInfo(): Flow<ServerInfo?> = serverInfo.asStateFlow()

        fun update(info: ServerInfo?) {
            serverInfo.value = info
        }
    }
