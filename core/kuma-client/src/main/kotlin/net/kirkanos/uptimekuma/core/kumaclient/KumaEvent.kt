package net.kirkanos.uptimekuma.core.kumaclient

import net.kirkanos.uptimekuma.core.kumaclient.dto.CertInfoPayloadDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatRowDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.InfoDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.MaintenanceDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto

/** Server -> client Socket.IO events, parsed into typed DTOs. */
sealed interface KumaEvent {
    /** `info`; arrives partial on connect and full after login (see [InfoDto.isFull]). */
    data class Info(
        val info: InfoDto,
    ) : KumaEvent

    /** `setup`: no user exists yet; the web UI must be used to create one. */
    data object Setup : KumaEvent

    /** `loginRequired`: handlers are registered, the client may now emit. */
    data object LoginRequired : KumaEvent

    /** `autoLogin`: auth is disabled on the server, the socket is already logged in. */
    data object AutoLogin : KumaEvent

    /** `refresh`: password changed / auth re-enabled; the stored token may be invalid now. */
    data object Refresh : KumaEvent

    /** `monitorList`: the complete list, replaces everything (keys arrive as strings). */
    data class MonitorList(
        val monitors: Map<Long, MonitorDto>,
    ) : KumaEvent

    /** `updateMonitorIntoList`: merge these entries into the local list. */
    data class MonitorUpdate(
        val monitors: Map<Long, MonitorDto>,
    ) : KumaEvent

    /** `deleteMonitorFromList`. */
    data class MonitorDeleted(
        val monitorId: Long,
    ) : KumaEvent

    /** `heartbeatList`: raw rows, oldest first; [overwrite] = replace instead of merge. */
    data class HeartbeatList(
        val monitorId: Long,
        val beats: List<HeartbeatRowDto>,
        val overwrite: Boolean,
    ) : KumaEvent

    /** `heartbeat`: one new beat. */
    data class NewHeartbeat(
        val beat: HeartbeatDto,
    ) : KumaEvent

    /** `uptime`: fraction 0..1 for one of the three periods. */
    data class Uptime(
        val monitorId: Long,
        val period: UptimePeriod,
        val fraction: Double,
    ) : KumaEvent

    /** `avgPing`: 24 h average in ms, `null` when unknown. */
    data class AvgPing(
        val monitorId: Long,
        val avgPingMs: Double?,
    ) : KumaEvent

    /** `certInfo` (payload is a JSON string on the wire; parsed here). */
    data class Cert(
        val monitorId: Long,
        val payload: CertInfoPayloadDto,
    ) : KumaEvent

    /** `maintenanceList`: all maintenances of the server keyed by id. */
    data class MaintenanceList(
        val maintenances: Map<Long, MaintenanceDto>,
    ) : KumaEvent

    /** `notificationList`. */
    data class NotificationList(
        val notifications: List<NotificationDto>,
    ) : KumaEvent
}

/** Period key of the `uptime` event: numbers `24`/`720` and the string `"1y"`. */
enum class UptimePeriod(
    val key: String,
) {
    DAY("24"),
    MONTH("720"),
    YEAR("1y"),
    ;

    companion object {
        fun fromKey(key: String?): UptimePeriod? = entries.firstOrNull { it.key == key?.trim() }
    }
}
