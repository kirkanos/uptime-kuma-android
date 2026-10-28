package net.kirkanos.uptimekuma.core.data.cache

import net.kirkanos.uptimekuma.core.data.db.CertEntity
import net.kirkanos.uptimekuma.core.data.db.HeartbeatEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorTagEntity
import net.kirkanos.uptimekuma.core.data.db.UptimeEntity
import net.kirkanos.uptimekuma.core.model.MonitorOverview

/** Joins the cached tables into [MonitorOverview]s. Pure, so it is unit tested directly. */
internal object OverviewBuilder {
    /**
     * Order: weight descending, then name (case-insensitive), then id. This is the order of Kuma's
     * `monitorList` (`ORDER BY weight DESC, name`); the list is flat, group children are ordered by
     * the same rule and can be nested by consumers via `parentId`.
     */
    val ORDER: Comparator<MonitorOverview> =
        compareByDescending<MonitorOverview> { it.monitor.weight }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.monitor.name }
            .thenBy { it.monitor.id }

    @Suppress("LongParameterList")
    fun build(
        monitors: List<MonitorEntity>,
        tags: List<MonitorTagEntity>,
        beats: List<HeartbeatEntity>,
        uptime: List<UptimeEntity>,
        certs: List<CertEntity>,
        maintenanceIds: Set<Long>,
    ): List<MonitorOverview> {
        val tagsByMonitor = tags.groupBy { it.monitorId }
        val beatsByMonitor = beats.groupBy { it.monitorId }
        val uptimeByMonitor = uptime.associateBy { it.monitorId }
        val certByMonitor = certs.associateBy { it.monitorId }
        val parents = monitors.associate { it.id to it.parentId }
        return monitors
            .map { entity ->
                val recent =
                    beatsByMonitor[entity.id]
                        .orEmpty()
                        .sortedBy { it.timeMillis }
                        .takeLast(HeartbeatBuffer.OVERVIEW_BEATS)
                        .map { it.toModel() }
                MonitorOverview(
                    monitor = entity.toModel(tagsByMonitor[entity.id].orEmpty()),
                    status = recent.lastOrNull()?.status,
                    recentBeats = recent,
                    uptime = uptimeByMonitor[entity.id]?.toModel(),
                    cert = certByMonitor[entity.id]?.toModel(),
                    inMaintenance =
                        entity.id in maintenanceIds ||
                            KumaEventSync.ancestors(entity.id, parents).any { it in maintenanceIds },
                )
            }.sortedWith(ORDER)
    }
}
