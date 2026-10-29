package net.kirkanos.uptimekuma.push.alerts

import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorStatus

/** File format v1 of the alert history; independent of the domain model so both can evolve separately. */
@Serializable
internal data class StoredAlerts(
    val alerts: List<StoredAlert> = emptyList(),
    val version: Int = 1,
)

@Serializable
internal data class StoredAlert(
    val receivedAt: Long,
    val monitorId: Long? = null,
    val monitorName: String? = null,
    /** Kuma status code, `null` for messages without heartbeat. */
    val status: Int? = null,
    val message: String = "",
    val eventTime: Long? = null,
    val notified: Boolean = true,
) {
    fun toModel(): AlertRecord =
        AlertRecord(
            receivedAtMillis = receivedAt,
            monitorId = monitorId,
            monitorName = monitorName,
            status = status?.let(MonitorStatus::fromCode),
            message = message,
            eventTimeMillis = eventTime,
            notified = notified,
        )

    companion object {
        fun of(record: AlertRecord): StoredAlert =
            StoredAlert(
                receivedAt = record.receivedAtMillis,
                monitorId = record.monitorId,
                monitorName = record.monitorName,
                status = record.status?.code,
                message = record.message,
                eventTime = record.eventTimeMillis,
                notified = record.notified,
            )
    }
}
