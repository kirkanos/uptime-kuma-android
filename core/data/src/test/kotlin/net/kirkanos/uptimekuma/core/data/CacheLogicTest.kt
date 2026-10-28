package net.kirkanos.uptimekuma.core.data

import net.kirkanos.uptimekuma.core.data.cache.HeartbeatBuffer
import net.kirkanos.uptimekuma.core.data.cache.KumaEventSync
import net.kirkanos.uptimekuma.core.data.cache.OverviewBuilder
import net.kirkanos.uptimekuma.core.data.cache.SyncTracker
import net.kirkanos.uptimekuma.core.data.cache.toEntity
import net.kirkanos.uptimekuma.core.data.cache.toIdList
import net.kirkanos.uptimekuma.core.data.cache.toIdString
import net.kirkanos.uptimekuma.core.data.cache.toModel
import net.kirkanos.uptimekuma.core.data.db.MonitorEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorTagEntity
import net.kirkanos.uptimekuma.core.data.db.UptimeEntity
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheLogicTest {
    private fun beat(
        time: Long,
        important: Boolean = false,
        status: MonitorStatus = MonitorStatus.UP,
        monitorId: Long = 1,
    ) = Heartbeat(monitorId = monitorId, status = status, timeMillis = time, important = important)

    @Test
    fun `ring buffer keeps the newest 150 plus older important beats`() {
        val beats = (1L..400L).map { beat(it, important = it % 50 == 0L) }
        val kept = HeartbeatBuffer.retain(beats)
        val recent = (251L..400L).toList()
        val olderImportant = listOf(50L, 100L, 150L, 200L, 250L).filter { it !in recent }
        assertEquals((olderImportant + recent).sorted(), kept.map { it.timeMillis })
    }

    @Test
    fun `important beats are capped too`() {
        val beats = (1L..20L).map { beat(it, important = true) }
        val kept = HeartbeatBuffer.retain(beats, keepRecent = 5, keepImportant = 8)
        assertEquals((13L..20L).toList(), kept.map { it.timeMillis })
    }

    @Test
    fun `merge dedups by time, prefers incoming, replace drops existing`() {
        val existing = listOf(beat(1), beat(2), beat(3))
        val incoming = listOf(beat(3, status = MonitorStatus.DOWN), beat(4))
        val merged = HeartbeatBuffer.merge(existing, incoming, replace = false)
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.timeMillis })
        assertEquals(MonitorStatus.DOWN, merged[2].status)

        val replaced = HeartbeatBuffer.merge(existing, incoming, replace = true)
        assertEquals(listOf(3L, 4L), replaced.map { it.timeMillis })
        assertEquals(emptyList<Heartbeat>(), HeartbeatBuffer.merge(existing, emptyList(), replace = true))
    }

    @Test
    fun `merge sorts out of order input oldest first`() {
        val merged = HeartbeatBuffer.merge(listOf(beat(5)), listOf(beat(3), beat(9), beat(1)), replace = false)
        assertEquals(listOf(1L, 3L, 5L, 9L), merged.map { it.timeMillis })
    }

    @Test
    fun `id lists round trip`() {
        assertEquals(listOf(12L, 13L), listOf(12L, 13L).toIdString().toIdList())
        assertEquals(emptyList<Long>(), "".toIdList())
    }

    @Test
    fun `maintenance expands to descendants with cycle guard`() {
        val parents = mapOf(1L to null, 11L to null, 12L to 11L, 13L to 11L, 14L to 13L, 20L to 21L, 21L to 20L)
        assertEquals(setOf(11L, 12L, 13L, 14L), KumaEventSync.withDescendants(setOf(11L), parents))
        assertEquals(setOf(13L, 14L), KumaEventSync.withDescendants(setOf(13L), parents))
        assertEquals(emptySet<Long>(), KumaEventSync.withDescendants(emptySet(), parents))
        assertEquals(listOf(21L), KumaEventSync.ancestors(20L, parents).toList())
    }

    @Test
    fun `sync tracker completes when every listed monitor has a heartbeat list`() {
        val tracker = SyncTracker()
        tracker.onEvent(KumaEvent.HeartbeatList(1, emptyList(), false))
        assertFalse(tracker.complete.value, "no monitor list yet")
        tracker.onEvent(Fixtures.first("monitorList"))
        assertFalse(tracker.complete.value)
        Fixtures.events("heartbeatList").dropLast(1).forEach(tracker::onEvent)
        assertFalse(tracker.complete.value)
        tracker.onEvent(KumaEvent.MonitorDeleted(13))
        assertTrue(tracker.complete.value, "deleted monitor no longer expected")
        tracker.reset()
        assertFalse(tracker.complete.value)
    }

    @Test
    fun `overview joins tables, sorts by weight then name and derives status`() {
        val monitors = (Fixtures.first("monitorList") as KumaEvent.MonitorList).monitors.values
        val entities =
            monitors.map { it.toEntity() } +
                MonitorEntity(
                    id = 99,
                    name = "aaa heavy",
                    type = "http",
                    description = null,
                    url = null,
                    hostname = null,
                    port = null,
                    active = true,
                    forceInactive = false,
                    parentId = null,
                    childrenIds = "",
                    pathName = "aaa heavy",
                    intervalSeconds = 60,
                    notificationIds = "",
                    expiryNotification = false,
                    weight = 3000,
                )
        val beats =
            (1L..120L).map { beat(it, monitorId = 1).toEntity() } +
                beat(5, status = MonitorStatus.DOWN, monitorId = 9).toEntity()
        val overviews =
            OverviewBuilder.build(
                monitors = entities,
                tags =
                    monitors.flatMap { dto ->
                        dto.tags.mapIndexed {
                            i,
                            t,
                            ->
                            MonitorTagEntity(dto.id, t.tagId, t.value.orEmpty(), t.name, t.color, i)
                        }
                    },
                beats = beats,
                uptime = listOf(UptimeEntity(1, uptime24h = 0.99)),
                certs = emptyList(),
                maintenanceIds = setOf(11L),
            )
        assertEquals(99L, overviews.first().monitor.id, "highest weight first")
        val names = overviews.drop(1).map { it.monitor.name }
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)

        val http = overviews.first { it.monitor.id == 1L }
        assertEquals(HeartbeatBuffer.OVERVIEW_BEATS, http.recentBeats.size)
        assertEquals(120L, http.lastBeat?.timeMillis)
        assertEquals(MonitorStatus.UP, http.status)
        assertEquals(0.99, http.uptime?.uptime24h)

        assertEquals(DisplayStatus.DOWN, overviews.first { it.monitor.id == 9L }.displayStatus)
        assertEquals(DisplayStatus.UNKNOWN, overviews.first { it.monitor.id == 2L }.displayStatus)
        assertEquals(
            listOf("env", "team"),
            overviews
                .first { it.monitor.id == 8L }
                .monitor.tags
                .map { it.name },
        )
        // Group 11 is in maintenance, so are its children.
        assertTrue(overviews.filter { it.monitor.id in setOf(11L, 12L, 13L) }.all { it.inMaintenance })
        assertFalse(overviews.first { it.monitor.id == 10L }.inMaintenance)
    }

    @Test
    fun `notification entities keep config and map to provider fields`() {
        val dto = (Fixtures.first("notificationList") as KumaEvent.NotificationList).notifications.first()
        val model = dto.toEntity().toModel()
        assertEquals("webhook", model.type)
        assertEquals("Dev webhook", model.name)
        assertEquals("json", model.fields["webhookContentType"])
        assertEquals(model, NotificationDto(1, "Dev webhook", config = dto.config).toModel())
    }
}
