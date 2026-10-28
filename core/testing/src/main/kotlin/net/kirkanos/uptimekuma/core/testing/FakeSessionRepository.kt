package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.ServerConfig

/**
 * In-memory [SessionRepository]. [loginHandler] decides the login outcome (default: success);
 * on success the server config is stored and the connection switches to [ConnectionState.Connected].
 */
class FakeSessionRepository(
    initialConfig: ServerConfig? = null,
    initialConnection: ConnectionState =
        if (initialConfig == null) ConnectionState.NotConfigured else ConnectionState.Disconnected,
) : SessionRepository {
    data class LoginCall(
        val baseUrl: String,
        val username: String,
        val password: String,
        val totp: String?,
    )

    val serverConfig = MutableStateFlow(initialConfig)
    val connectionState = MutableStateFlow(initialConnection)

    var loginHandler: suspend (LoginCall) -> LoginResult = { LoginResult.Success }

    /** Artificial latency of [login] and [syncOnce], e.g. to show progress UI in a demo build. */
    var delayMillis: Long = 0

    var syncResult: Boolean = true
    var syncError: Throwable? = null

    val loginCalls = mutableListOf<LoginCall>()
    var logoutCount = 0
        private set
    var syncCount = 0
        private set
    val foregroundSignals = mutableListOf<Boolean>()

    override fun observeServerConfig(): Flow<ServerConfig?> = serverConfig

    override fun observeConnectionState(): Flow<ConnectionState> = connectionState

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totp: String?,
    ): LoginResult {
        val call = LoginCall(baseUrl, username, password, totp)
        loginCalls += call
        if (delayMillis > 0) delay(delayMillis)
        val result = loginHandler(call)
        if (result == LoginResult.Success) {
            serverConfig.value = ServerConfig(baseUrl = baseUrl, username = username)
            connectionState.value = ConnectionState.Connected(SampleData.serverInfo)
        }
        return result
    }

    override suspend fun logout() {
        logoutCount++
        serverConfig.value = null
        connectionState.value = ConnectionState.NotConfigured
    }

    override fun setForeground(foreground: Boolean) {
        foregroundSignals += foreground
        if (serverConfig.value == null) return
        connectionState.value =
            if (foreground) ConnectionState.Connected(SampleData.serverInfo) else ConnectionState.Disconnected
    }

    override suspend fun syncOnce(timeoutMillis: Long): Boolean {
        syncCount++
        if (delayMillis > 0) delay(delayMillis)
        syncError?.let { throw it }
        return syncResult
    }
}
