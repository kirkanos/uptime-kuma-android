package net.kirkanos.uptimekuma.core.data.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import net.kirkanos.uptimekuma.core.data.LogoutHook
import net.kirkanos.uptimekuma.core.data.cache.KumaCache
import net.kirkanos.uptimekuma.core.data.cache.KumaEventSync
import net.kirkanos.uptimekuma.core.data.cache.SyncTracker
import net.kirkanos.uptimekuma.core.data.config.ServerConfigStore
import net.kirkanos.uptimekuma.core.data.di.DataScope
import net.kirkanos.uptimekuma.core.data.secret.SecretStore
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.KumaClientState
import net.kirkanos.uptimekuma.core.kumaclient.KumaEndpoint
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.KumaEventParser
import net.kirkanos.uptimekuma.core.kumaclient.KumaUrls
import net.kirkanos.uptimekuma.core.kumaclient.LoginResponse
import net.kirkanos.uptimekuma.core.kumaclient.dto.InfoDto
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.ServerInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one live socket to the configured Kuma server and keeps the Room cache in sync.
 *
 * Lifecycle:
 * - `setForeground(true)` opens the socket; on `loginRequired` the stored JWT is sent via
 *   `loginByToken`; all events are then written to the cache.
 * - `setForeground(false)` closes the socket after [ConnectionTimings.backgroundGraceMillis].
 * - Socket.IO reconnects with backoff; each reconnect re-runs `loginByToken`.
 * - A rejected token or a `refresh` event clears the token -> [ConnectionState.LoginRequired].
 * - Post-login `info.version` must be 2.x, else [KumaError.Kind.UNSUPPORTED_SERVER].
 * - One-off calls ([withApi], [syncOnce]) open a transient connection when in background
 *   ("leases"); it is closed again when the last lease is released.
 */
@Singleton
@Suppress("TooManyFunctions")
internal class KumaConnectionManager
    @Inject
    constructor(
        private val apiFactory: KumaApiFactory,
        private val cache: KumaCache,
        private val configStore: ServerConfigStore,
        private val secretStore: SecretStore,
        private val logoutHooks: Set<@JvmSuppressWildcards LogoutHook>,
        @DataScope private val scope: CoroutineScope,
        private val timings: ConnectionTimings,
    ) {
        private val sync = KumaEventSync(cache)
        private val mutex = Mutex()
        private var session: Session? = null
        private var leases = 0
        private var graceJob: Job? = null

        @Volatile private var foreground = false

        @Volatile private var interactiveLogin = false

        private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

        private val mutableServerInfo = MutableStateFlow<ServerInfo?>(null)
        val serverInfo: StateFlow<ServerInfo?> = mutableServerInfo.asStateFlow()

        val serverConfig get() = configStore.config

        private val snapshots = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<JsonElement>?>>()

        /**
         * Latest raw arguments of a list event from [KumaEventParser.SNAPSHOT_EVENTS] (e.g.
         * `statusPageList`, `proxyList`), `null` until received after login. Survives reconnects,
         * cleared on logout. Feature repositories decode these themselves.
         */
        fun snapshot(event: String): StateFlow<List<JsonElement>?> {
            require(event in KumaEventParser.SNAPSHOT_EVENTS) { "$event is not a snapshot event" }
            return snapshots.getOrPut(event) { MutableStateFlow(null) }.asStateFlow()
        }

        init {
            scope.launch {
                mutex.withLock {
                    if (session == null &&
                        configStore.current() == null
                    ) {
                        mutableState.value = ConnectionState.NotConfigured
                    }
                }
            }
        }

        /** One socket plus the coroutines that feed its events into the cache. */
        private inner class Session(
            val api: KumaApi,
            val baseUrl: String,
        ) {
            val job = SupervisorJob(this@KumaConnectionManager.scope.coroutineContext[Job])
            val sessionScope = CoroutineScope(this@KumaConnectionManager.scope.coroutineContext + job)
            val authenticated = MutableStateFlow(false)
            val fullInfo = MutableStateFlow<ServerInfo?>(null)

            /** Set when any post-login `info` reported an unsupported version. */
            @Volatile var unsupportedInfo: ServerInfo? = null
            val tracker = SyncTracker()

            /** Data events wait here until the socket is authenticated; keeps order, never blocks the socket. */
            val pending = Channel<KumaEvent>(Channel.UNLIMITED)

            @Volatile var closed = false
        }

        // ---- public API --------------------------------------------------------------------------

        fun setForeground(isForeground: Boolean) {
            foreground = isForeground
            graceJob?.cancel()
            graceJob =
                scope.launch {
                    if (isForeground) {
                        mutex.withLock { ensureSessionLocked() }
                    } else {
                        delay(timings.backgroundGraceMillis)
                        mutex.withLock {
                            if (!foreground &&
                                leases == 0
                            ) {
                                session?.let { closeSessionLocked(it, ConnectionState.Disconnected) }
                            }
                        }
                    }
                }
        }

        /** Runs [block] with an authenticated socket, connecting transiently if needed. */
        suspend fun <T> withApi(
            timeoutMillis: Long = timings.readyTimeoutMillis,
            block: suspend (KumaApi) -> T,
        ): T = withSession(timeoutMillis) { block(it.api) }

        suspend fun syncOnce(timeoutMillis: Long): Boolean {
            if (configStore.current() == null || secretStore.get(TOKEN_KEY) == null) return false
            return try {
                withTimeout(timeoutMillis) {
                    withSession(timeoutMillis) { s ->
                        s.tracker.complete.first { it }
                        delay(timings.syncDrainMillis)
                    }
                }
                true
            } catch (e: TimeoutCancellationException) {
                throw KumaException(KumaError(KumaError.Kind.TIMEOUT, "Sync did not complete"), e)
            } catch (e: KumaException) {
                if (e.error.kind == KumaError.Kind.UNAUTHORIZED || e.error.kind == KumaError.Kind.INVALID_CREDENTIALS) {
                    false
                } else {
                    throw e
                }
            }
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
        suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
            totp: String?,
        ): LoginResult {
            val url =
                try {
                    KumaUrls.normalize(baseUrl).also { KumaEndpoint.parse(it) }
                } catch (e: IllegalArgumentException) {
                    return LoginResult.Failure(KumaError(KumaError.Kind.NETWORK, e.message))
                }
            interactiveLogin = true
            var current: Session? = null
            var keepSession = false
            try {
                val s =
                    mutex.withLock {
                        graceJob?.cancel()
                        val reusable = session?.takeIf { it.baseUrl == url && !it.closed && !it.authenticated.value }
                        if (reusable == null) session?.let { closeSessionLocked(it, null) }
                        leases++
                        reusable ?: openSessionLocked(url)
                    }
                current = s
                val ready = s.api.awaitReady(timings.readyTimeoutMillis)
                val token =
                    if (ready.autoLogin) {
                        AUTO_LOGIN_TOKEN
                    } else {
                        if (ready.setupRequired || s.api.needSetup()) {
                            return LoginResult.Failure(KumaError(KumaError.Kind.SETUP_REQUIRED))
                        }
                        when (val response = s.api.login(username.trim(), password, totp)) {
                            LoginResponse.TwoFactorRequired -> {
                                keepSession = true
                                return LoginResult.TwoFactorRequired
                            }

                            is LoginResponse.Success -> {
                                response.token
                            }
                        }
                    }
                val info = withTimeoutOrNull(timings.infoTimeoutMillis) { s.fullInfo.filterNotNull().first() }
                s.unsupportedInfo?.let { return unsupported(it) }
                if (info != null && !isSupported(info.version)) return unsupported(info)
                if (info == null) DataLog.w("No post-login info from $url, skipping version gate")

                val previous = configStore.current()
                val newConfig = ServerConfig(baseUrl = url, username = username.trim())
                if (previous == null || previous.baseUrl != newConfig.baseUrl ||
                    previous.username != newConfig.username
                ) {
                    cache.clear()
                }
                configStore.save(newConfig)
                secretStore.put(TOKEN_KEY, token)
                markAuthenticated(s)
                keepSession = true
                return LoginResult.Success
            } catch (e: KumaException) {
                return LoginResult.Failure(e.error)
            } finally {
                interactiveLogin = false
                withContext(NonCancellable) { finishLogin(current, keepSession) }
            }
        }

        suspend fun logout() {
            logoutHooks.forEach { hook ->
                try {
                    hook.onLogout()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DataLog.w("Logout hook failed", e)
                }
            }
            mutex.withLock {
                session?.let { s ->
                    if (s.authenticated.value) {
                        try {
                            withTimeoutOrNull(timings.logoutTimeoutMillis) { s.api.logout() }
                        } catch (e: KumaException) {
                            DataLog.w("Server logout failed", e)
                        }
                    }
                    closeSessionLocked(s, null)
                }
                secretStore.remove(TOKEN_KEY)
                cache.clear()
                snapshots.values.forEach { it.value = null }
                configStore.clear()
                mutableServerInfo.value = null
                mutableState.value = ConnectionState.NotConfigured
            }
        }

        // ---- sessions ---------------------------------------------------------------------------

        private suspend fun <T> withSession(
            timeoutMillis: Long,
            block: suspend (Session) -> T,
        ): T {
            val s =
                mutex.withLock { ensureSessionLocked()?.also { leases++ } }
                    ?: throw KumaException(KumaError(KumaError.Kind.UNAUTHORIZED, "Not logged in"))
            try {
                awaitAuthenticated(s, timeoutMillis)
                return block(s)
            } finally {
                withContext(NonCancellable) { release(s) }
            }
        }

        private suspend fun awaitAuthenticated(
            s: Session,
            timeoutMillis: Long,
        ) {
            withTimeoutOrNull(timeoutMillis) {
                combine(s.authenticated, mutableState) { auth, st -> auth || s.closed || st.isTerminal() }.first { it }
            }
            if (s.authenticated.value && !s.closed) return
            throw KumaException(
                when (val st = mutableState.value) {
                    ConnectionState.LoginRequired, ConnectionState.NotConfigured -> {
                        KumaError(KumaError.Kind.UNAUTHORIZED, "Login required")
                    }

                    is ConnectionState.Failed -> {
                        st.error
                    }

                    else -> {
                        KumaError(KumaError.Kind.TIMEOUT, "Not connected to the server")
                    }
                },
            )
        }

        private fun ConnectionState.isTerminal(): Boolean =
            this is ConnectionState.LoginRequired ||
                this is ConnectionState.NotConfigured ||
                (this is ConnectionState.Failed && error.kind == KumaError.Kind.UNSUPPORTED_SERVER)

        private suspend fun release(s: Session) {
            mutex.withLock {
                leases--
                if (leases == 0 && !foreground && session === s) closeSessionLocked(s, ConnectionState.Disconnected)
            }
        }

        private suspend fun finishLogin(
            s: Session?,
            keep: Boolean,
        ) {
            mutex.withLock {
                leases--
                if (s == null || session !== s) return@withLock
                when {
                    !keep -> {
                        closeSessionLocked(s, null)
                        restoreStateLocked()
                    }

                    leases == 0 && !foreground -> {
                        closeSessionLocked(s, ConnectionState.Disconnected)
                    }
                }
            }
        }

        /** After a failed interactive login: fall back to the stored session (if any). */
        private suspend fun restoreStateLocked() {
            if (foreground) {
                ensureSessionLocked()
                return
            }
            mutableState.value =
                when {
                    configStore.current() == null -> ConnectionState.NotConfigured
                    secretStore.get(TOKEN_KEY) == null -> ConnectionState.LoginRequired
                    else -> ConnectionState.Disconnected
                }
        }

        private suspend fun ensureSessionLocked(): Session? {
            // An interactive login owns the socket until it finished.
            if (interactiveLogin) return session
            val config = configStore.current()
            val hasToken = secretStore.get(TOKEN_KEY) != null
            val existing = session?.takeIf { !it.closed }
            return when {
                config == null -> {
                    mutableState.value = ConnectionState.NotConfigured
                    null
                }

                !hasToken -> {
                    mutableState.value = ConnectionState.LoginRequired
                    null
                }

                existing != null && existing.baseUrl == config.baseUrl -> {
                    existing
                }

                else -> {
                    existing?.let { closeSessionLocked(it, null) }
                    openSessionLocked(config.baseUrl)
                }
            }
        }

        private fun openSessionLocked(baseUrl: String): Session {
            val s = Session(apiFactory.create(baseUrl), baseUrl)
            session = s
            mutableState.value = ConnectionState.Connecting
            s.sessionScope.launch(start = CoroutineStart.UNDISPATCHED) { s.api.events.collect { onEvent(s, it) } }
            s.sessionScope.launch(start = CoroutineStart.UNDISPATCHED) { s.api.state.collect { onClientState(s, it) } }
            s.sessionScope.launch { for (event in s.pending) applyWhenAuthenticated(s, event) }
            s.api.connect()
            return s
        }

        private fun closeSessionLocked(
            s: Session,
            newState: ConnectionState?,
        ) {
            if (session === s) session = null
            s.closed = true
            s.authenticated.value = false
            s.api.close()
            s.pending.close()
            s.job.cancel()
            mutableServerInfo.value = null
            newState?.let { mutableState.value = it }
        }

        /** Closes [s] from one of its own coroutines (which would otherwise cancel themselves mid-way). */
        private fun requestClose(
            s: Session,
            newState: ConnectionState,
        ) {
            scope.launch { mutex.withLock { if (!s.closed) closeSessionLocked(s, newState) } }
        }

        private fun setState(
            s: Session,
            newState: ConnectionState,
        ) {
            if (session === s && !s.closed) mutableState.value = newState
        }

        // ---- socket callbacks -------------------------------------------------------------------

        private suspend fun onClientState(
            s: Session,
            clientState: KumaClientState,
        ) {
            when (clientState) {
                KumaClientState.Idle -> {}

                KumaClientState.Connecting -> {
                    if (!s.authenticated.value) setState(s, ConnectionState.Connecting)
                }

                KumaClientState.Connected -> {
                    // A new transport connection is a new, unauthenticated server socket.
                    s.authenticated.value = false
                    s.tracker.reset()
                    setState(s, ConnectionState.Connecting)
                }

                is KumaClientState.Ready -> {
                    onReady(s, clientState)
                }

                is KumaClientState.Disconnected -> {
                    s.authenticated.value = false
                    if (clientState.reconnecting) setState(s, ConnectionState.Connecting)
                }

                is KumaClientState.ConnectFailed -> {
                    setState(s, ConnectionState.Failed(KumaError(KumaError.Kind.NETWORK, clientState.message)))
                }
            }
        }

        private suspend fun onReady(
            s: Session,
            ready: KumaClientState.Ready,
        ) {
            if (ready.autoLogin) {
                markAuthenticated(s)
                return
            }
            if (interactiveLogin) return
            val token = secretStore.get(TOKEN_KEY)
            if (token == null || token == AUTO_LOGIN_TOKEN) {
                secretStore.remove(TOKEN_KEY)
                requestClose(s, ConnectionState.LoginRequired)
                return
            }
            try {
                s.api.loginByToken(token)
                markAuthenticated(s)
            } catch (e: KumaException) {
                when (e.error.kind) {
                    KumaError.Kind.INVALID_CREDENTIALS, KumaError.Kind.UNAUTHORIZED -> {
                        DataLog.i("Stored token rejected: ${e.error.message}")
                        secretStore.remove(TOKEN_KEY)
                        requestClose(s, ConnectionState.LoginRequired)
                    }

                    // Socket dropped mid-request: the reconnect triggers another Ready.
                    KumaError.Kind.NETWORK -> {}

                    else -> {
                        setState(s, ConnectionState.Failed(e.error, timings.tokenRetryMillis))
                        s.sessionScope.launch {
                            delay(timings.tokenRetryMillis)
                            val again = s.api.state.value
                            if (again is KumaClientState.Ready && !s.authenticated.value) onReady(s, again)
                        }
                    }
                }
            }
        }

        private fun markAuthenticated(s: Session) {
            s.authenticated.value = true
            s.fullInfo.value?.let { setState(s, ConnectionState.Connected(it)) }
            s.sessionScope.launch {
                try {
                    cache.replaceTags(s.api.getTags())
                } catch (e: KumaException) {
                    DataLog.w("getTags failed", e)
                }
            }
        }

        private fun onEvent(
            s: Session,
            event: KumaEvent,
        ) {
            when {
                event is KumaEvent.Info -> {
                    onInfo(s, event.info)
                }

                event == KumaEvent.Refresh -> {
                    // Password changed or auth re-enabled: the token may be invalid now.
                    scope.launch { secretStore.remove(TOKEN_KEY) }
                    requestClose(s, ConnectionState.LoginRequired)
                }

                event is KumaEvent.Raw || sync.isDataEvent(event) -> {
                    s.pending.trySend(event)
                }
            }
        }

        private fun onInfo(
            s: Session,
            dto: InfoDto,
        ) {
            val info = dto.toModel() ?: return
            s.fullInfo.value = info
            if (!isSupported(info.version)) {
                s.unsupportedInfo = info
                requestClose(s, unsupportedState(info))
                return
            }
            if (!info.version.startsWith(TESTED_VERSION_PREFIX)) {
                DataLog.w("Kuma ${info.version} is untested (built against 2.5.x); continuing")
            }
            if (session === s && !s.closed) mutableServerInfo.value = info
            if (s.authenticated.value) setState(s, ConnectionState.Connected(info))
        }

        private suspend fun applyWhenAuthenticated(
            s: Session,
            event: KumaEvent,
        ) {
            s.authenticated.first { it }
            if (event is KumaEvent.Raw) {
                snapshots.getOrPut(event.name) { MutableStateFlow(null) }.value = event.args
                return
            }
            try {
                sync.apply(event, s.api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataLog.w("Cannot apply $event", e)
            }
            s.tracker.onEvent(event)
        }

        private fun unsupported(info: ServerInfo): LoginResult.Failure =
            LoginResult.Failure(unsupportedState(info).error)

        private fun unsupportedState(info: ServerInfo) =
            ConnectionState.Failed(
                KumaError(
                    KumaError.Kind.UNSUPPORTED_SERVER,
                    "Uptime Kuma ${info.version} is not supported (2.x required)",
                ),
            )

        companion object {
            /** SecretStore key of the Kuma JWT. */
            const val TOKEN_KEY = "kuma.jwt.1"

            /** Stored instead of a JWT when the server runs with authentication disabled (`autoLogin`). */
            const val AUTO_LOGIN_TOKEN = "auto-login"
            private const val SUPPORTED_MAJOR = 2
            private const val TESTED_VERSION_PREFIX = "2.5."

            fun isSupported(version: String): Boolean =
                version.substringBefore('.').trim().toIntOrNull() == SUPPORTED_MAJOR
        }
    }
