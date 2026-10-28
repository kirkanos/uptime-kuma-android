package net.kirkanos.uptimekuma.core.data.cache

import net.kirkanos.uptimekuma.core.kumaclient.UptimePeriod
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Tag

/**
 * Write side of the local cache, used by the live sync ([KumaEventSync]) and the repositories.
 * Implemented on Room by [RoomKumaCache]; tests use an in-memory fake.
 */
@Suppress("TooManyFunctions")
internal interface KumaCache {
    /** `monitorList`: replaces all monitors (deleting missing ones with their beats/stats) and their tags. */
    suspend fun replaceMonitors(monitors: Collection<MonitorDto>)

    /** `updateMonitorIntoList`: upserts the given monitors and replaces their tags. */
    suspend fun upsertMonitors(monitors: Collection<MonitorDto>)

    /** `deleteMonitorFromList` / local delete: removes the monitor and everything attached. */
    suspend fun deleteMonitor(monitorId: Long)

    /** Optimistic local update after pause/resume. */
    suspend fun setMonitorActive(
        monitorId: Long,
        active: Boolean,
    )

    /** `id -> parentId` of all cached monitors. */
    suspend fun monitorParents(): Map<Long, Long?>

    /** `heartbeatList`: [replace] drops the existing beats of that monitor first; otherwise merges. */
    suspend fun putHeartbeats(
        monitorId: Long,
        beats: List<Heartbeat>,
        replace: Boolean,
    )

    /** `heartbeat`: appends one beat (and trims the ring buffer). */
    suspend fun addHeartbeat(beat: Heartbeat)

    suspend fun putUptime(
        monitorId: Long,
        period: UptimePeriod,
        fraction: Double,
    )

    suspend fun putAvgPing(
        monitorId: Long,
        avgPingMs: Double?,
    )

    suspend fun putCert(cert: CertInfo)

    /** `notificationList`: replaces all notifications. */
    suspend fun replaceNotifications(notifications: List<NotificationDto>)

    suspend fun deleteNotification(id: Long)

    /** `getTags`: replaces the tag definitions. */
    suspend fun replaceTags(tags: List<Tag>)

    /** Replaces the set of monitors directly covered by an active maintenance. */
    suspend fun replaceMaintenanceMonitors(monitorIds: Set<Long>)

    suspend fun setMaintenance(
        monitorId: Long,
        inMaintenance: Boolean,
    )

    /** Logout / server switch: drops everything. */
    suspend fun clear()
}
