package net.kirkanos.uptimekuma.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One monitor from `monitorList` / `updateMonitorIntoList`. Tags live in [MonitorTagEntity]. */
@Entity(tableName = "monitors")
data class MonitorEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val type: String,
    val description: String?,
    val url: String?,
    val hostname: String?,
    val port: Int?,
    val active: Boolean,
    val forceInactive: Boolean,
    val parentId: Long?,
    /** Comma separated ids. */
    val childrenIds: String,
    val pathName: String,
    val intervalSeconds: Int,
    /** Comma separated ids. */
    val notificationIds: String,
    val expiryNotification: Boolean,
    val weight: Int,
)

/** `MonitorJSON.tags` entry; the same tag may be attached several times with different values. */
@Entity(
    tableName = "monitor_tags",
    primaryKeys = ["monitorId", "tagId", "value"],
    indices = [Index("monitorId")],
)
data class MonitorTagEntity(
    val monitorId: Long,
    val tagId: Long,
    val value: String,
    val name: String,
    val color: String,
    /** Order within the monitor's tag list. */
    val position: Int,
)

/** Tag definitions (`getTags`, plus the ones seen on monitors). */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val color: String,
)

/**
 * Cached heartbeats. Kuma's `heartbeat` event has no row id, so (monitorId, timeMillis) is the key;
 * it is identical for the same beat in `heartbeatList`, `heartbeat` and `getMonitorBeats`.
 * Trimmed per monitor by [net.kirkanos.uptimekuma.core.data.cache.HeartbeatBuffer].
 */
@Entity(
    tableName = "heartbeats",
    primaryKeys = ["monitorId", "timeMillis"],
    indices = [Index(value = ["monitorId", "important", "timeMillis"])],
)
data class HeartbeatEntity(
    val monitorId: Long,
    val timeMillis: Long,
    val status: Int,
    val message: String,
    val pingMs: Double?,
    val important: Boolean,
    val durationSeconds: Int?,
    val retries: Int,
)

/** `uptime` (three periods) and `avgPing` per monitor. */
@Entity(tableName = "uptime")
data class UptimeEntity(
    @PrimaryKey val monitorId: Long,
    val uptime24h: Double? = null,
    val uptime30d: Double? = null,
    val uptime1y: Double? = null,
    val avgPingMs: Double? = null,
)

/** Leaf certificate from `certInfo`. */
@Entity(tableName = "cert_info")
data class CertEntity(
    @PrimaryKey val monitorId: Long,
    val valid: Boolean,
    val subject: String?,
    val issuer: String?,
    val validFromMillis: Long?,
    val validToMillis: Long?,
    val daysRemaining: Int?,
    val fingerprint256: String?,
)

/** `notificationList` entry; provider settings stay in the raw JSON [config] string (as Kuma stores them). */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val active: Boolean,
    val isDefault: Boolean,
    val config: String?,
)

/** Monitors currently covered by an active maintenance (directly; descendants are derived). */
@Entity(tableName = "maintenance_monitors")
data class MaintenanceMonitorEntity(
    @PrimaryKey val monitorId: Long,
)
