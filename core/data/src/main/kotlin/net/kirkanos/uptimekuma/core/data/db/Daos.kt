package net.kirkanos.uptimekuma.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorDao {
    @Query("SELECT * FROM monitors")
    fun observeAll(): Flow<List<MonitorEntity>>

    @Query("SELECT * FROM monitor_tags ORDER BY monitorId, position")
    fun observeAllTags(): Flow<List<MonitorTagEntity>>

    @Query("SELECT id, parentId FROM monitors")
    suspend fun hierarchy(): List<MonitorParent>

    @Upsert
    suspend fun upsert(monitors: List<MonitorEntity>)

    @Query("DELETE FROM monitors WHERE id NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<Long>)

    @Query("DELETE FROM monitors WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE monitors SET active = :active WHERE id = :id")
    suspend fun setActive(
        id: Long,
        active: Boolean,
    )

    @Query("DELETE FROM monitors")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsertTags(tags: List<MonitorTagEntity>)

    @Query("DELETE FROM monitor_tags WHERE monitorId IN (:monitorIds)")
    suspend fun deleteTagsOf(monitorIds: List<Long>)

    @Query("DELETE FROM monitor_tags")
    suspend fun deleteAllTags()
}

/** Projection for parent lookups. */
data class MonitorParent(
    val id: Long,
    val parentId: Long?,
)

@Dao
interface HeartbeatDao {
    @Query("SELECT * FROM heartbeats ORDER BY monitorId, timeMillis")
    fun observeAll(): Flow<List<HeartbeatEntity>>

    @Upsert
    suspend fun upsert(beats: List<HeartbeatEntity>)

    @Query("DELETE FROM heartbeats WHERE monitorId = :monitorId")
    suspend fun deleteOf(monitorId: Long)

    @Query("DELETE FROM heartbeats WHERE monitorId NOT IN (SELECT id FROM monitors)")
    suspend fun deleteOrphans()

    @Query("DELETE FROM heartbeats")
    suspend fun deleteAll()

    /**
     * Ring buffer: keeps the newest [keepRecent] beats of a monitor plus its newest [keepImportant]
     * important beats. Mirrors [net.kirkanos.uptimekuma.core.data.cache.HeartbeatBuffer.retain].
     */
    @Query(
        """
        DELETE FROM heartbeats WHERE monitorId = :monitorId
          AND timeMillis NOT IN (
            SELECT timeMillis FROM heartbeats WHERE monitorId = :monitorId
            ORDER BY timeMillis DESC LIMIT :keepRecent)
          AND NOT (important = 1 AND timeMillis IN (
            SELECT timeMillis FROM heartbeats WHERE monitorId = :monitorId AND important = 1
            ORDER BY timeMillis DESC LIMIT :keepImportant))
        """,
    )
    suspend fun trim(
        monitorId: Long,
        keepRecent: Int,
        keepImportant: Int,
    )
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM uptime")
    fun observeUptime(): Flow<List<UptimeEntity>>

    @Query("SELECT * FROM uptime WHERE monitorId = :monitorId")
    suspend fun uptime(monitorId: Long): UptimeEntity?

    @Upsert
    suspend fun upsertUptime(uptime: UptimeEntity)

    @Query("SELECT * FROM cert_info")
    fun observeCerts(): Flow<List<CertEntity>>

    @Upsert
    suspend fun upsertCert(cert: CertEntity)

    @Query("DELETE FROM uptime WHERE monitorId NOT IN (SELECT id FROM monitors)")
    suspend fun deleteOrphanUptime()

    @Query("DELETE FROM cert_info WHERE monitorId NOT IN (SELECT id FROM monitors)")
    suspend fun deleteOrphanCerts()

    @Query("DELETE FROM uptime WHERE monitorId = :monitorId")
    suspend fun deleteUptimeOf(monitorId: Long)

    @Query("DELETE FROM cert_info WHERE monitorId = :monitorId")
    suspend fun deleteCertOf(monitorId: Long)

    @Query("DELETE FROM uptime")
    suspend fun deleteAllUptime()

    @Query("DELETE FROM cert_info")
    suspend fun deleteAllCerts()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Upsert
    suspend fun upsert(notifications: List<NotificationEntity>)

    @Query("DELETE FROM notifications WHERE id NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<Long>)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<TagEntity>>

    @Upsert
    suspend fun upsert(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE id NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<Long>)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}

@Dao
interface MaintenanceDao {
    @Query("SELECT monitorId FROM maintenance_monitors")
    fun observeIds(): Flow<List<Long>>

    @Upsert
    suspend fun upsert(entries: List<MaintenanceMonitorEntity>)

    @Query("DELETE FROM maintenance_monitors WHERE monitorId = :monitorId")
    suspend fun delete(monitorId: Long)

    @Query("DELETE FROM maintenance_monitors")
    suspend fun deleteAll()
}
