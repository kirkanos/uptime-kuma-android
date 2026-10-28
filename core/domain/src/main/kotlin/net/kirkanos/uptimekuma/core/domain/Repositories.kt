package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.Tag

/*
 * Contract between :core:data (implementation) and features/push/widget (consumers).
 * All suspend functions throw net.kirkanos.uptimekuma.core.model.KumaException on failure.
 * Flows are backed by the local cache (Room), so they emit immediately, also offline.
 */

/** Server configuration, login and connection lifecycle. */
interface SessionRepository {
    fun observeServerConfig(): Flow<ServerConfig?>

    fun observeConnectionState(): Flow<ConnectionState>

    /**
     * Interactive login. Connects to [baseUrl], logs in and on success stores the server
     * config and the JWT (encrypted). Pass [totp] after [LoginResult.TwoFactorRequired].
     */
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        totp: String? = null,
    ): LoginResult

    /** Logs out on the server (best effort), clears token, cache and push registration. */
    suspend fun logout()

    /**
     * Foreground/background signal from the app process lifecycle. While true a live socket
     * is kept (with reconnect); after false the socket is closed after a short grace period.
     */
    fun setForeground(foreground: Boolean)

    /**
     * One-shot sync for background work (push received, periodic check): connect, log in by
     * token, wait for monitor list + latest heartbeats, update the cache, disconnect unless
     * the app is in foreground. Returns false when not configured or login is required.
     */
    suspend fun syncOnce(timeoutMillis: Long = 20_000): Boolean
}

/** Monitors, heartbeats and live statistics. */
interface MonitorRepository {
    /** All monitors with status, sorted by Kuma's order (weight, then name). */
    fun observeMonitors(): Flow<List<MonitorOverview>>

    fun observeMonitor(id: Long): Flow<MonitorOverview?>

    /** Fetches beats of the last [hours] from the server (`getMonitorBeats`), oldest first. */
    suspend fun loadBeats(
        id: Long,
        hours: Int,
    ): List<Heartbeat>

    /** `getMonitorChartData` for [periodHours] (e.g. 3, 6, 24, 168). */
    suspend fun loadChart(
        id: Long,
        periodHours: Int,
    ): List<ChartPoint>

    /** Important events (state changes), newest first. [monitorId] null = all monitors. */
    suspend fun loadImportantEvents(
        monitorId: Long?,
        offset: Int,
        count: Int,
    ): List<Heartbeat>

    suspend fun pause(id: Long)

    suspend fun resume(id: Long)

    suspend fun delete(id: Long)

    fun observeTags(): Flow<List<Tag>>
}

/** Notification providers configured in Kuma. */
interface KumaNotificationRepository {
    fun observeNotifications(): Flow<List<KumaNotification>>

    /**
     * Creates ([KumaNotification.id] == null) or updates a notification (`addNotification`).
     * [applyExisting] attaches it to all existing monitors. Returns the notification id.
     */
    suspend fun save(
        notification: KumaNotification,
        applyExisting: Boolean = false,
    ): Long

    suspend fun delete(id: Long)

    /** Sends a test message through the given (unsaved) configuration (`testNotification`). */
    suspend fun test(notification: KumaNotification)
}
