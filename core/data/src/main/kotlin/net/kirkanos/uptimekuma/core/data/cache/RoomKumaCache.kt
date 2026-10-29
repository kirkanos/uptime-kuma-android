package net.kirkanos.uptimekuma.core.data.cache

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import net.kirkanos.uptimekuma.core.data.db.KumaDatabase
import net.kirkanos.uptimekuma.core.data.db.MaintenanceMonitorEntity
import net.kirkanos.uptimekuma.core.data.db.TagEntity
import net.kirkanos.uptimekuma.core.data.db.UptimeEntity
import net.kirkanos.uptimekuma.core.kumaclient.UptimePeriod
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Tag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions")
internal class RoomKumaCache
    @Inject
    constructor(
        private val db: KumaDatabase,
    ) : KumaCache {
        private val monitors get() = db.monitorDao()
        private val heartbeats get() = db.heartbeatDao()
        private val stats get() = db.statsDao()
        private val maintenance get() = db.maintenanceDao()

        /**
         * One write transaction; DAO calls inside [block] run on the same writer connection. Uses Room's
         * connection API (instead of `withTransaction`) so the cache also works with an `SQLiteDriver`,
         * e.g. the bundled driver in JVM tests.
         */
        private suspend fun <R> transaction(block: suspend () -> R): R =
            db.useWriterConnection { connection -> connection.immediateTransaction { block() } }

        override suspend fun replaceMonitors(monitors: Collection<MonitorDto>) =
            transaction {
                val ids = monitors.map { it.id }
                this.monitors.deleteAllExcept(ids)
                this.monitors.deleteAllTags()
                writeMonitors(monitors)
                heartbeats.deleteOrphans()
                stats.deleteOrphanUptime()
                stats.deleteOrphanCerts()
                maintenance.deleteAll()
                maintenance.upsert(monitors.filter { it.maintenance }.map { MaintenanceMonitorEntity(it.id) })
            }

        override suspend fun upsertMonitors(monitors: Collection<MonitorDto>) =
            transaction {
                this.monitors.deleteTagsOf(monitors.map { it.id })
                writeMonitors(monitors)
                monitors.forEach { setMaintenanceInternal(it.id, it.maintenance) }
            }

        private suspend fun writeMonitors(list: Collection<MonitorDto>) {
            monitors.upsert(list.map { it.toEntity() })
            monitors.upsertTags(list.flatMap { it.tagEntities() })
            val tagDefinitions = list.flatMap { it.tags }.distinctBy { it.tagId }
            db.tagDao().upsert(tagDefinitions.map { TagEntity(it.tagId, it.name, it.color) })
        }

        override suspend fun deleteMonitor(monitorId: Long) =
            transaction {
                monitors.delete(monitorId)
                monitors.deleteTagsOf(listOf(monitorId))
                heartbeats.deleteOf(monitorId)
                stats.deleteUptimeOf(monitorId)
                stats.deleteCertOf(monitorId)
                maintenance.delete(monitorId)
            }

        override suspend fun setMonitorActive(
            monitorId: Long,
            active: Boolean,
        ) = monitors.setActive(monitorId, active)

        override suspend fun monitorParents(): Map<Long, Long?> =
            monitors.hierarchy().associate { it.id to it.parentId }

        override suspend fun putHeartbeats(
            monitorId: Long,
            beats: List<Heartbeat>,
            replace: Boolean,
        ) = transaction {
            if (replace) heartbeats.deleteOf(monitorId)
            heartbeats.upsert(beats.filter { it.monitorId == monitorId }.map { it.toEntity() })
            heartbeats.trim(monitorId, HeartbeatBuffer.KEEP_RECENT, HeartbeatBuffer.KEEP_IMPORTANT)
        }

        override suspend fun addHeartbeat(beat: Heartbeat) =
            transaction {
                heartbeats.upsert(listOf(beat.toEntity()))
                heartbeats.trim(beat.monitorId, HeartbeatBuffer.KEEP_RECENT, HeartbeatBuffer.KEEP_IMPORTANT)
            }

        override suspend fun putUptime(
            monitorId: Long,
            period: UptimePeriod,
            fraction: Double,
        ) = updateUptime(monitorId) {
            when (period) {
                UptimePeriod.DAY -> it.copy(uptime24h = fraction)
                UptimePeriod.MONTH -> it.copy(uptime30d = fraction)
                UptimePeriod.YEAR -> it.copy(uptime1y = fraction)
            }
        }

        override suspend fun putAvgPing(
            monitorId: Long,
            avgPingMs: Double?,
        ) = updateUptime(monitorId) { it.copy(avgPingMs = avgPingMs) }

        private suspend fun updateUptime(
            monitorId: Long,
            change: (UptimeEntity) -> UptimeEntity,
        ) = transaction {
            stats.upsertUptime(change(stats.uptime(monitorId) ?: UptimeEntity(monitorId)))
        }

        override suspend fun putCert(cert: CertInfo) = stats.upsertCert(cert.toEntity())

        override suspend fun replaceNotifications(notifications: List<NotificationDto>) =
            transaction {
                val dao = db.notificationDao()
                dao.deleteAllExcept(notifications.map { it.id })
                dao.upsert(notifications.map { it.toEntity() })
            }

        override suspend fun deleteNotification(id: Long) = db.notificationDao().delete(id)

        override suspend fun replaceTags(tags: List<Tag>) =
            transaction {
                val dao = db.tagDao()
                dao.deleteAllExcept(tags.map { it.id })
                dao.upsert(tags.map { it.toEntity() })
            }

        override suspend fun replaceMaintenanceMonitors(monitorIds: Set<Long>) =
            transaction {
                maintenance.deleteAll()
                maintenance.upsert(monitorIds.map { MaintenanceMonitorEntity(it) })
            }

        override suspend fun setMaintenance(
            monitorId: Long,
            inMaintenance: Boolean,
        ) = setMaintenanceInternal(monitorId, inMaintenance)

        private suspend fun setMaintenanceInternal(
            monitorId: Long,
            inMaintenance: Boolean,
        ) {
            if (inMaintenance) {
                maintenance.upsert(listOf(MaintenanceMonitorEntity(monitorId)))
            } else {
                maintenance.delete(monitorId)
            }
        }

        override suspend fun clear() =
            transaction {
                monitors.deleteAll()
                monitors.deleteAllTags()
                heartbeats.deleteAll()
                stats.deleteAllUptime()
                stats.deleteAllCerts()
                db.notificationDao().deleteAll()
                db.tagDao().deleteAll()
                maintenance.deleteAll()
            }
    }
