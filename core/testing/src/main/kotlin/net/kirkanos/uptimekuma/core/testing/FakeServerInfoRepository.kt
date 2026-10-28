package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.model.ServerInfo

/** In-memory [ServerInfoRepository] for unit tests. */
class FakeServerInfoRepository(
    initial: ServerInfo? = null,
) : ServerInfoRepository {
    private val state = MutableStateFlow(initial)

    fun emit(info: ServerInfo?) {
        state.value = info
    }

    override fun observeServerInfo(): Flow<ServerInfo?> = state
}
