package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Tag

/**
 * Typed access to one Uptime Kuma server over Socket.IO. Implemented by [KumaClient]; the
 * data layer depends on this interface so it can be faked in tests.
 *
 * All suspend functions throw [net.kirkanos.uptimekuma.core.model.KumaException] on failure and
 * wait (bounded by their timeout) until the server signalled readiness ([KumaClientState.Ready]).
 */
@Suppress("TooManyFunctions")
interface KumaApi {
    val baseUrl: String

    val state: StateFlow<KumaClientState>

    /** Hot stream of server events. Subscribe before [connect] to not miss the login burst. */
    val events: Flow<KumaEvent>

    /** Opens the socket (no-op when already open). Reconnects automatically with backoff. */
    fun connect()

    /** Closes the socket and stops reconnecting. [connect] may be called again later. */
    fun disconnect()

    /** Disconnects and releases all resources; the instance cannot be used afterwards. */
    fun close() = disconnect()

    /** Suspends until the server sent `loginRequired`/`autoLogin` on the current connection. */
    suspend fun awaitReady(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): KumaClientState.Ready

    /** `needSetup`: true when the server has no user yet. */
    suspend fun needSetup(): Boolean

    /** `login`. [totp] only after [LoginResponse.TwoFactorRequired]. */
    suspend fun login(
        username: String,
        password: String,
        totp: String? = null,
    ): LoginResponse

    /** `loginByToken`; throws INVALID_CREDENTIALS when the token is rejected. */
    suspend fun loginByToken(token: String)

    /** `logout` (ack without arguments). */
    suspend fun logout()

    suspend fun getMonitorBeats(
        monitorId: Long,
        hours: Int,
    ): List<Heartbeat>

    suspend fun getMonitorChartData(
        monitorId: Long,
        periodHours: Int,
    ): List<ChartPoint>

    /** `monitorImportantHeartbeatListPaged`, newest first. [monitorId] `null` = all monitors. */
    suspend fun importantHeartbeats(
        monitorId: Long?,
        offset: Int,
        count: Int,
    ): List<Heartbeat>

    suspend fun importantHeartbeatCount(monitorId: Long?): Int

    suspend fun pauseMonitor(monitorId: Long)

    suspend fun resumeMonitor(monitorId: Long)

    suspend fun deleteMonitor(
        monitorId: Long,
        deleteChildren: Boolean = false,
    )

    suspend fun getTags(): List<Tag>

    /** `getMonitorMaintenance`: ids of the monitors directly attached to a maintenance. */
    suspend fun getMonitorMaintenance(maintenanceId: Long): List<Long>

    /** `addNotification`; [id] `null` creates. Returns the notification id. */
    suspend fun addNotification(
        notification: JsonObject,
        id: Long?,
    ): Long

    suspend fun deleteNotification(id: Long)

    /** `testNotification`; returns the provider's message (e.g. "Sent Successfully."). */
    suspend fun testNotification(notification: JsonObject): String?

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000
    }
}

/** Typed view on [KumaApi.events]. */
inline fun <reified T : KumaEvent> KumaApi.on(): Flow<T> = events.filterIsInstance<T>()

/** Result of a successful `login` ack. Failures are thrown as KumaException. */
sealed interface LoginResponse {
    data class Success(
        val token: String,
    ) : LoginResponse

    /** `{tokenRequired: true}` (no `ok` field): call again with a TOTP code. */
    data object TwoFactorRequired : LoginResponse
}

/** Lifecycle of the underlying socket. */
sealed interface KumaClientState {
    /** Never connected or [KumaApi.disconnect] was called. */
    data object Idle : KumaClientState

    data object Connecting : KumaClientState

    /** Transport is open, but Kuma has not signalled readiness yet; emits would be dropped. */
    data object Connected : KumaClientState

    /** `loginRequired` ([autoLogin] = false) or `autoLogin` received. [setupRequired] when `setup` was sent. */
    data class Ready(
        val autoLogin: Boolean,
        val setupRequired: Boolean = false,
    ) : KumaClientState

    /** Lost the connection; [reconnecting] when Socket.IO will retry. */
    data class Disconnected(
        val reason: String?,
        val reconnecting: Boolean,
    ) : KumaClientState

    /** A connection attempt failed; [reconnecting] when Socket.IO will retry. */
    data class ConnectFailed(
        val message: String?,
        val reconnecting: Boolean,
    ) : KumaClientState
}
