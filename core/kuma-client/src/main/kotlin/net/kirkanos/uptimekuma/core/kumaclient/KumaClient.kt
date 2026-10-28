package net.kirkanos.uptimekuma.core.kumaclient

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import net.kirkanos.uptimekuma.core.kumaclient.dto.ChartPointDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatRowDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.TagDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.toJsonElement
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Tag
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Minimal logging hook so the pure JVM client does not depend on Android logging. */
fun interface KumaLogger {
    fun log(
        message: String,
        throwable: Throwable?,
    )

    companion object {
        val NONE: KumaLogger = KumaLogger { _, _ -> }
    }
}

/**
 * Socket.IO client for one Uptime Kuma 2.x server.
 *
 * Handles the quirks of the real server (see docs/kuma-api/README.md):
 * - nothing is emitted before `loginRequired`/`autoLogin` (Kuma registers its handlers only after
 *   `sendInfo`; earlier emits are silently dropped), see [awaitReady];
 * - no `Origin` header is sent (Kuma rejects a WebSocket upgrade whose Origin does not match Host);
 * - WebSocket transport first, falling back once to polling(+upgrade) if the first attempt fails;
 * - a base URL sub-path becomes the engine path (`basePath + "/socket.io/"`), not the namespace.
 */
@Suppress("TooManyFunctions")
class KumaClient(
    baseUrl: String,
    private val okHttpClient: OkHttpClient? = null,
    val defaultTimeoutMillis: Long = KumaApi.DEFAULT_TIMEOUT_MILLIS,
    private val logger: KumaLogger = KumaLogger.NONE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : KumaApi {
    override val baseUrl: String = KumaUrls.normalize(baseUrl)
    private val endpoint = KumaEndpoint.parse(this.baseUrl)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val incoming = Channel<KumaEvent>(Channel.UNLIMITED)
    private val sharedEvents = MutableSharedFlow<KumaEvent>()
    override val events: Flow<KumaEvent> = sharedEvents.asSharedFlow()

    private val mutableState = MutableStateFlow<KumaClientState>(KumaClientState.Idle)
    override val state: StateFlow<KumaClientState> = mutableState.asStateFlow()

    private val lock = Any()
    private var socket: Socket? = null
    private var everConnected = false
    private var pollingFallback = false

    @Volatile private var setupSeen = false
    private val pendingAcks = ConcurrentHashMap.newKeySet<CompletableDeferred<List<JsonElement>>>()

    init {
        // Socket.IO callbacks run on its event thread; an unbounded channel keeps their order and
        // never blocks that thread, the pump applies back pressure towards slow collectors.
        scope.launch { for (event in incoming) sharedEvents.emit(event) }
    }

    override fun connect() {
        synchronized(lock) {
            if (socket != null) return
            mutableState.value = KumaClientState.Connecting
            socket = createSocket().also { it.connect() }
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            socket?.let {
                it.off()
                it.disconnect()
            }
            socket = null
        }
        failPendingAcks("Disconnected")
        mutableState.value = KumaClientState.Idle
    }

    override fun close() {
        disconnect()
        incoming.close()
        scope.cancel()
    }

    override suspend fun awaitReady(timeoutMillis: Long): KumaClientState.Ready {
        val result =
            withTimeoutOrNull(timeoutMillis) {
                state.first { it is KumaClientState.Ready || it is KumaClientState.Idle }
            }
        return when (result) {
            is KumaClientState.Ready -> {
                result
            }

            KumaClientState.Idle -> {
                throw KumaException(KumaError(KumaError.Kind.NETWORK, "Not connected"))
            }

            else -> {
                val current = state.value
                val message = (current as? KumaClientState.ConnectFailed)?.message
                if (current is KumaClientState.ConnectFailed || current is KumaClientState.Disconnected) {
                    throw KumaException(KumaError(KumaError.Kind.NETWORK, message ?: "Cannot reach server"))
                }
                throw KumaException(KumaError(KumaError.Kind.TIMEOUT, "Server did not become ready"))
            }
        }
    }

    /**
     * Emits [event] with [args] (JsonElement, String, numbers, Boolean, null, Map/List) once the
     * server is ready and returns the raw acknowledgement arguments (may be empty).
     */
    override suspend fun requestRaw(
        event: String,
        args: List<Any?>,
        timeoutMillis: Long,
    ): List<JsonElement> {
        awaitReady(timeoutMillis)
        val current =
            synchronized(lock) { socket }
                ?: throw KumaException(KumaError(KumaError.Kind.NETWORK, "Not connected"))
        val deferred = CompletableDeferred<List<JsonElement>>()
        pendingAcks += deferred
        try {
            val socketArgs = args.map { toSocketArg(it) }.toTypedArray()
            current.emit(event, socketArgs, Ack { ackArgs -> deferred.complete(ackArgs.map { fromSocketArg(it) }) })
            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: throw KumaException(KumaError(KumaError.Kind.TIMEOUT, "No answer to '$event'"))
        } finally {
            pendingAcks -= deferred
        }
    }

    /** Emits [event] and returns the ack object, throwing [KumaException] for `ok:false`. */
    suspend fun requestOk(
        event: String,
        vararg args: Any?,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): JsonObject = KumaAcks.requireOk(event, requestRaw(event, args.toList(), timeoutMillis).firstOrNull())

    /** Generic request: emits [event], checks `ok` and decodes the whole ack object into [T]. */
    suspend inline fun <reified T> request(
        event: String,
        vararg args: Any?,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): T = KumaJson.json.decodeFromJsonElement(requestOk(event, *args, timeoutMillis = timeoutMillis))

    override suspend fun needSetup(): Boolean =
        requestRaw("needSetup", emptyList()).firstOrNull().asLenientBoolean()
            ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "Unexpected needSetup answer"))

    override suspend fun login(
        username: String,
        password: String,
        totp: String?,
    ): LoginResponse {
        val payload =
            buildMap<String, Any?> {
                put("username", username)
                put("password", password)
                if (!totp.isNullOrBlank()) put("token", totp)
            }
        return KumaAcks.loginResponse(requestRaw("login", listOf(payload.toJsonElement())).firstOrNull())
    }

    override suspend fun loginByToken(token: String) {
        requestOk("loginByToken", token)
    }

    override suspend fun logout() {
        requestRaw("logout", emptyList())
    }

    override suspend fun getMonitorBeats(
        monitorId: Long,
        hours: Int,
    ): List<Heartbeat> =
        request<DataAck<List<HeartbeatRowDto>>>("getMonitorBeats", monitorId, hours)
            .data
            .mapNotNull { it.toModel(monitorId) }
            .sortedBy { it.timeMillis }

    override suspend fun getMonitorChartData(
        monitorId: Long,
        periodHours: Int,
    ): List<ChartPoint> =
        request<DataAck<List<ChartPointDto>>>("getMonitorChartData", monitorId, periodHours)
            .data
            .map { it.toModel() }
            .sortedBy { it.timeMillis }

    override suspend fun importantHeartbeats(
        monitorId: Long?,
        offset: Int,
        count: Int,
    ): List<Heartbeat> =
        request<DataAck<List<HeartbeatDto>>>("monitorImportantHeartbeatListPaged", monitorId, offset, count)
            .data
            .mapNotNull { it.toModel() }

    override suspend fun importantHeartbeatCount(monitorId: Long?): Int =
        requestOk("monitorImportantHeartbeatListCount", monitorId)["count"].asLenientLong()?.toInt() ?: 0

    override suspend fun pauseMonitor(monitorId: Long) {
        requestOk("pauseMonitor", monitorId)
    }

    override suspend fun resumeMonitor(monitorId: Long) {
        requestOk("resumeMonitor", monitorId)
    }

    override suspend fun deleteMonitor(
        monitorId: Long,
        deleteChildren: Boolean,
    ) {
        requestOk("deleteMonitor", monitorId, deleteChildren)
    }

    override suspend fun getTags(): List<Tag> {
        val tags = requestOk("getTags")["tags"] ?: return emptyList()
        return KumaJson.json.decodeFromJsonElement(ListSerializer(TagDto.serializer()), tags).map { it.toModel() }
    }

    override suspend fun getMonitorMaintenance(maintenanceId: Long): List<Long> {
        val monitors = requestOk("getMonitorMaintenance", maintenanceId)["monitors"] as? JsonArray ?: return emptyList()
        return monitors.mapNotNull { (it as? JsonObject)?.get("id").asLenientLong() }
    }

    override suspend fun addNotification(
        notification: JsonObject,
        id: Long?,
    ): Long =
        requestOk("addNotification", notification, id)["id"].asLenientLong()
            ?: id
            ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "addNotification ack without id"))

    override suspend fun deleteNotification(id: Long) {
        requestOk("deleteNotification", id)
    }

    override suspend fun testNotification(notification: JsonObject): String? =
        KumaAcks.messageOf(requestOk("testNotification", notification, timeoutMillis = TEST_NOTIFICATION_TIMEOUT))

    private fun createSocket(): Socket {
        val transports = if (pollingFallback) arrayOf(POLLING, WEBSOCKET) else arrayOf(WEBSOCKET)
        val options =
            IO.Options
                .builder()
                .setForceNew(true)
                .setMultiplex(false)
                .setPath(endpoint.socketPath)
                .setTransports(transports)
                .setUpgrade(true)
                .setReconnection(true)
                .setReconnectionDelay(RECONNECTION_DELAY_MS)
                .setReconnectionDelayMax(RECONNECTION_DELAY_MAX_MS)
                .setTimeout(CONNECT_TIMEOUT_MS)
                .build()
        okHttpClient?.let {
            options.callFactory = it
            options.webSocketFactory = it
        }
        val created = IO.socket(endpoint.origin, options)
        created.on(Socket.EVENT_CONNECT) { onTransportConnected(created) }
        created.on(Socket.EVENT_DISCONNECT) { args -> onTransportDisconnected(created, args) }
        created.on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(created, args) }
        KumaEventParser.EVENTS.forEach { name ->
            created.on(name) { args -> onServerEvent(created, name, args) }
        }
        return created
    }

    private fun isCurrent(candidate: Socket): Boolean = synchronized(lock) { socket === candidate }

    private fun onTransportConnected(source: Socket) {
        if (!isCurrent(source)) return
        everConnected = true
        setupSeen = false
        mutableState.value = KumaClientState.Connected
    }

    private fun onTransportDisconnected(
        source: Socket,
        args: Array<Any?>,
    ) {
        if (!isCurrent(source)) return
        val reason = args.firstOrNull()?.toString()
        failPendingAcks("Connection lost: $reason")
        mutableState.value =
            KumaClientState.Disconnected(
                reason,
                reconnecting =
                    reason != CLIENT_DISCONNECT && source.io().reconnection(),
            )
    }

    private fun onConnectError(
        source: Socket,
        args: Array<Any?>,
    ) {
        val error = args.firstOrNull() as? Throwable
        // Engine.IO wraps the transport failure ("websocket error"); the root cause is what matters.
        val rootCause = generateSequence(error) { it.cause }.lastOrNull()
        val message =
            args.firstOrNull()?.let { arg ->
                listOfNotNull(
                    (arg as? Throwable)?.message ?: arg.toString(),
                    rootCause?.takeIf { it !== error }?.toString(),
                ).joinToString(": ")
            }
        synchronized(lock) {
            if (socket !== source) return
            if (!everConnected && !pollingFallback) {
                logger.log("WebSocket connect failed ($message), falling back to polling", error)
                pollingFallback = true
                source.off()
                source.close()
                socket = createSocket().also { it.connect() }
                return
            }
        }
        mutableState.value = KumaClientState.ConnectFailed(message, reconnecting = source.io().reconnection())
    }

    private fun onServerEvent(
        source: Socket,
        name: String,
        args: Array<Any?>,
    ) {
        if (!isCurrent(source)) return
        val jsonArgs = args.map { fromSocketArg(it) }
        if (name in KumaEventParser.SNAPSHOT_EVENTS) incoming.trySend(KumaEvent.Raw(name, jsonArgs))
        val event =
            try {
                KumaEventParser.parse(name, jsonArgs)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                logger.log("Cannot parse '$name'", e)
                null
            } ?: return
        when (event) {
            KumaEvent.Setup -> setupSeen = true
            KumaEvent.LoginRequired -> mutableState.value = KumaClientState.Ready(autoLogin = false, setupSeen)
            KumaEvent.AutoLogin -> mutableState.value = KumaClientState.Ready(autoLogin = true, setupSeen)
            else -> Unit
        }
        incoming.trySend(event)
    }

    private fun failPendingAcks(reason: String) {
        val error = KumaException(KumaError(KumaError.Kind.NETWORK, reason))
        pendingAcks.forEach { it.completeExceptionally(error) }
    }

    /** `{ok:true, data:...}` acks. */
    @Serializable
    @PublishedApi
    internal data class DataAck<T>(
        val data: T,
    )

    private companion object {
        const val WEBSOCKET = "websocket"
        const val POLLING = "polling"
        const val CLIENT_DISCONNECT = "io client disconnect"
        const val RECONNECTION_DELAY_MS = 1_000L
        const val RECONNECTION_DELAY_MAX_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val TEST_NOTIFICATION_TIMEOUT = 30_000L

        fun toSocketArg(value: Any?): Any =
            when (value) {
                null, JsonNull -> {
                    JSONObject.NULL
                }

                is JsonObject -> {
                    JSONObject(value.toString())
                }

                is JsonArray -> {
                    JSONArray(value.toString())
                }

                is JsonPrimitive -> {
                    if (value.isString) {
                        value.content
                    } else {
                        value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
                    }
                }

                is String, is Boolean, is Int, is Long, is Double -> {
                    value
                }

                else -> {
                    toSocketArg(value.toJsonElement())
                }
            }

        fun fromSocketArg(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                JSONObject.NULL -> JsonNull
                is JSONObject, is JSONArray -> KumaJson.json.parseToJsonElement(value.toString())
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value.toLong())
                is Long -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value.toDouble())
                else -> JsonPrimitive(value.toString())
            }
    }
}
