package net.kirkanos.uptimekuma.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.data.cache.HeartbeatBuffer
import net.kirkanos.uptimekuma.core.data.cache.KumaCache
import net.kirkanos.uptimekuma.core.data.config.ServerConfigStore
import net.kirkanos.uptimekuma.core.data.secret.SecretStore
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.KumaClientState
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.KumaEventParser
import net.kirkanos.uptimekuma.core.kumaclient.LoginResponse
import net.kirkanos.uptimekuma.core.kumaclient.UptimePeriod
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.model.UptimeStats
import java.io.File

/** Recorded Kuma 2.5.8 events from `fixtures/kuma-2.5.8`. */
object Fixtures {
    private val dir: File by lazy {
        val configured = System.getProperty("kuma.fixtures")?.let(::File)
        listOfNotNull(configured, File("../../fixtures/kuma-2.5.8")).first { it.isDirectory }
    }

    private fun json(name: String): JsonObject = KumaJson.json.parseToJsonElement(File(dir, name).readText()).jsonObject

    fun events(event: String): List<KumaEvent> =
        json("$event.json").getValue("samples").jsonArray.map { sample ->
            val args: List<JsonElement> = (sample.jsonObject["args"] as JsonArray).toList()
            requireNotNull(KumaEventParser.parse(event, args))
        }

    fun first(event: String): KumaEvent = events(event).first()

    /** Post-login burst in Kuma's order, followed by live events. */
    fun loginBurst(): List<KumaEvent> =
        listOf(first("monitorList")) +
            events("info").last() +
            first("maintenanceList") +
            first("notificationList") +
            events("heartbeatList") +
            events("uptime") +
            events("avgPing") +
            events("certInfo")
}

/** In-memory [KumaCache] with the same semantics as the Room one. */
internal class FakeKumaCache : KumaCache {
    val monitors = linkedMapOf<Long, MonitorDto>()
    val beats = mutableMapOf<Long, List<Heartbeat>>()
    val uptime = mutableMapOf<Long, UptimeStats>()
    val certs = mutableMapOf<Long, CertInfo>()
    val notifications = linkedMapOf<Long, NotificationDto>()
    val tags = mutableListOf<Tag>()
    val maintenance = mutableSetOf<Long>()
    var clears = 0

    override suspend fun replaceMonitors(monitors: Collection<MonitorDto>) {
        this.monitors.clear()
        monitors.forEach { this.monitors[it.id] = it }
        beats.keys.retainAll(this.monitors.keys)
        uptime.keys.retainAll(this.monitors.keys)
        certs.keys.retainAll(this.monitors.keys)
        maintenance.clear()
        maintenance += monitors.filter { it.maintenance }.map { it.id }
    }

    override suspend fun upsertMonitors(monitors: Collection<MonitorDto>) {
        monitors.forEach {
            this.monitors[it.id] = it
            setMaintenance(it.id, it.maintenance)
        }
    }

    override suspend fun deleteMonitor(monitorId: Long) {
        monitors.remove(monitorId)
        beats.remove(monitorId)
        uptime.remove(monitorId)
        certs.remove(monitorId)
        maintenance.remove(monitorId)
    }

    override suspend fun setMonitorActive(
        monitorId: Long,
        active: Boolean,
    ) {
        monitors[monitorId]?.let { monitors[monitorId] = it.copy(active = active) }
    }

    override suspend fun monitorParents(): Map<Long, Long?> = monitors.mapValues { it.value.parent }

    override suspend fun putHeartbeats(
        monitorId: Long,
        beats: List<Heartbeat>,
        replace: Boolean,
    ) {
        this.beats[monitorId] = HeartbeatBuffer.merge(this.beats[monitorId].orEmpty(), beats, replace)
    }

    override suspend fun addHeartbeat(beat: Heartbeat) {
        beats[beat.monitorId] = HeartbeatBuffer.merge(beats[beat.monitorId].orEmpty(), listOf(beat), replace = false)
    }

    override suspend fun putUptime(
        monitorId: Long,
        period: UptimePeriod,
        fraction: Double,
    ) {
        val current = uptime[monitorId] ?: UptimeStats(monitorId)
        uptime[monitorId] =
            when (period) {
                UptimePeriod.DAY -> current.copy(uptime24h = fraction)
                UptimePeriod.MONTH -> current.copy(uptime30d = fraction)
                UptimePeriod.YEAR -> current.copy(uptime1y = fraction)
            }
    }

    override suspend fun putAvgPing(
        monitorId: Long,
        avgPingMs: Double?,
    ) {
        uptime[monitorId] = (uptime[monitorId] ?: UptimeStats(monitorId)).copy(avgPingMs = avgPingMs)
    }

    override suspend fun putCert(cert: CertInfo) {
        certs[cert.monitorId] = cert
    }

    override suspend fun replaceNotifications(notifications: List<NotificationDto>) {
        this.notifications.clear()
        notifications.forEach { this.notifications[it.id] = it }
    }

    override suspend fun deleteNotification(id: Long) {
        notifications.remove(id)
    }

    override suspend fun replaceTags(tags: List<Tag>) {
        this.tags.clear()
        this.tags += tags
    }

    override suspend fun replaceMaintenanceMonitors(monitorIds: Set<Long>) {
        maintenance.clear()
        maintenance += monitorIds
    }

    override suspend fun setMaintenance(
        monitorId: Long,
        inMaintenance: Boolean,
    ) {
        if (inMaintenance) maintenance += monitorId else maintenance -= monitorId
    }

    override suspend fun clear() {
        clears++
        monitors.clear()
        beats.clear()
        uptime.clear()
        certs.clear()
        notifications.clear()
        tags.clear()
        maintenance.clear()
    }
}

internal class FakeSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()

    override suspend fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun get(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

internal class FakeConfigStore(
    initial: ServerConfig? = null,
) : ServerConfigStore {
    private val state = MutableStateFlow(initial)
    override val config: Flow<ServerConfig?> = state

    override suspend fun save(config: ServerConfig) {
        state.value = config
    }

    override suspend fun clear() {
        state.value = null
    }
}

/**
 * Scriptable [KumaApi]: `connect()` moves to Ready, logins emit [postLoginEvents] like Kuma's
 * `afterLogin`, and every call is recorded in [calls].
 */
@Suppress("TooManyFunctions")
internal class FakeKumaApi(
    override val baseUrl: String,
) : KumaApi {
    private val mutableState = MutableStateFlow<KumaClientState>(KumaClientState.Idle)
    override val state: StateFlow<KumaClientState> = mutableState
    val eventFlow = MutableSharedFlow<KumaEvent>(extraBufferCapacity = 10_000)
    override val events: Flow<KumaEvent> = eventFlow

    val calls = mutableListOf<String>()
    var closed = false
    var readyState: KumaClientState = KumaClientState.Ready(autoLogin = false)
    var needSetup = false
    var validToken = "jwt-1"
    var loginResponse: (String, String, String?) -> LoginResponse = { _, _, _ -> LoginResponse.Success(validToken) }
    var postLoginEvents: List<KumaEvent> = Fixtures.loginBurst()
    var maintenanceMonitors: Map<Long, List<Long>> = mapOf(1L to listOf(10L))

    override fun connect() {
        calls += "connect"
        mutableState.value = readyState
    }

    override fun disconnect() {
        calls += "disconnect"
        mutableState.value = KumaClientState.Idle
    }

    override fun close() {
        closed = true
        disconnect()
    }

    fun setState(state: KumaClientState) {
        mutableState.value = state
    }

    suspend fun emit(event: KumaEvent) = eventFlow.emit(event)

    override suspend fun awaitReady(timeoutMillis: Long): KumaClientState.Ready =
        withTimeoutOrNull(timeoutMillis) { state.first { it is KumaClientState.Ready } as KumaClientState.Ready }
            ?: throw KumaException(KumaError(KumaError.Kind.TIMEOUT))

    override suspend fun needSetup(): Boolean = needSetup.also { calls += "needSetup" }

    override suspend fun login(
        username: String,
        password: String,
        totp: String?,
    ): LoginResponse {
        calls += "login:$username"
        val response = loginResponse(username, password, totp)
        if (response is LoginResponse.Success) postLoginEvents.forEach { eventFlow.emit(it) }
        return response
    }

    override suspend fun loginByToken(token: String) {
        calls += "loginByToken:$token"
        if (token != validToken) throw KumaException(KumaError(KumaError.Kind.INVALID_CREDENTIALS, "authInvalidToken"))
        postLoginEvents.forEach { eventFlow.emit(it) }
    }

    override suspend fun logout() {
        calls += "logout"
    }

    override suspend fun getMonitorBeats(
        monitorId: Long,
        hours: Int,
    ): List<Heartbeat> = emptyList<Heartbeat>().also { calls += "getMonitorBeats:$monitorId:$hours" }

    override suspend fun getMonitorChartData(
        monitorId: Long,
        periodHours: Int,
    ): List<ChartPoint> = emptyList()

    override suspend fun importantHeartbeats(
        monitorId: Long?,
        offset: Int,
        count: Int,
    ): List<Heartbeat> = emptyList()

    override suspend fun importantHeartbeatCount(monitorId: Long?): Int = 0

    override suspend fun pauseMonitor(monitorId: Long) {
        calls += "pause:$monitorId"
    }

    override suspend fun resumeMonitor(monitorId: Long) {
        calls += "resume:$monitorId"
    }

    override suspend fun deleteMonitor(
        monitorId: Long,
        deleteChildren: Boolean,
    ) {
        calls += "delete:$monitorId"
    }

    override suspend fun getTags(): List<Tag> = listOf(Tag(1, "env", "#059669"), Tag(2, "team", "#7C3AED"))

    override suspend fun getMonitorMaintenance(maintenanceId: Long): List<Long> =
        maintenanceMonitors[maintenanceId].orEmpty().also { calls += "getMonitorMaintenance:$maintenanceId" }

    override suspend fun addNotification(
        notification: JsonObject,
        id: Long?,
    ): Long = (id ?: NEW_NOTIFICATION_ID).also { calls += "addNotification:$notification" }

    override suspend fun deleteNotification(id: Long) {
        calls += "deleteNotification:$id"
    }

    override suspend fun testNotification(notification: JsonObject): String? = "Sent Successfully."

    companion object {
        const val NEW_NOTIFICATION_ID = 42L
    }
}
