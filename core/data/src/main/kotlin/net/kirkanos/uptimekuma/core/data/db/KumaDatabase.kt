package net.kirkanos.uptimekuma.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local cache of one Kuma server. Everything in here can be re-fetched, but the schema is versioned
 * (exported to `core/data/schemas`) so future changes ship real migrations.
 */
@Database(
    entities = [
        MonitorEntity::class,
        MonitorTagEntity::class,
        TagEntity::class,
        HeartbeatEntity::class,
        UptimeEntity::class,
        CertEntity::class,
        NotificationEntity::class,
        MaintenanceMonitorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KumaDatabase : RoomDatabase() {
    abstract fun monitorDao(): MonitorDao

    abstract fun heartbeatDao(): HeartbeatDao

    abstract fun statsDao(): StatsDao

    abstract fun notificationDao(): NotificationDao

    abstract fun tagDao(): TagDao

    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "kuma-cache.db"
    }
}
