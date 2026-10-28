package net.kirkanos.uptimekuma.core.kumaclient

import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Parses every recorded server -> client event of Kuma 2.5.8 and maps it to models. */
class EventFixtureTest {
    @Test
    fun `info is partial before login and full afterwards`() {
        val (partial, full) = Fixtures.events("info").map { (it as KumaEvent.Info).info }
        assertFalse(partial.isFull)
        assertNull(partial.toModel())
        assertEquals("Europe/Berlin", partial.serverTimezone)
        val info = requireNotNull(full.toModel())
        assertEquals("2.5.8", info.version)
        assertEquals("2.5.5", info.latestVersion)
        assertNull(info.primaryBaseUrl)
        assertEquals(true, full.isContainer)
    }

    @Test
    fun `loginRequired has no payload`() {
        assertEquals(listOf(KumaEvent.LoginRequired), Fixtures.events("loginRequired"))
    }

    @Test
    fun `monitorList is keyed by string ids and maps to models`() {
        Fixtures.events("monitorList").forEach { event ->
            val monitors = (event as KumaEvent.MonitorList).monitors
            assertEquals((1L..13L).toSet(), monitors.keys)
            monitors.forEach { (id, dto) -> assertEquals(id, dto.id) }
        }
        val monitors = (Fixtures.events("monitorList").first() as KumaEvent.MonitorList).monitors
        val models = monitors.mapValues { it.value.toModel() }

        val http = models.getValue(1)
        assertEquals("HTTP example.com", http.name)
        assertEquals("http", http.type)
        assertEquals(20, http.intervalSeconds)
        assertEquals(2000, http.weight)
        assertTrue(http.active)
        assertNotNull(http.target)

        val group = models.getValue(11)
        assertTrue(group.isGroup)
        assertEquals(listOf(12L, 13L), group.childrenIds)
        assertNull(group.target, "group url 'https://' is not a target")

        val child = models.getValue(12)
        assertEquals(11L, child.parentId)
        assertEquals("Group Web / Group child HTTP example.com", child.pathName)

        val tagged = models.getValue(8)
        assertEquals(listOf("env" to "dev", "team" to "android"), tagged.tags.map { it.name to it.value })
        assertEquals("#059669", tagged.tags.first().color)

        assertEquals(setOf(1L, 2L), models.getValue(9).notificationIds)
        assertTrue(monitors.getValue(10).maintenance)
        assertFalse(monitors.getValue(1).maintenance)
        assertEquals("example.com:443", models.getValue(4).target)
    }

    @Test
    fun `updateMonitorIntoList carries single entries incl pause`() {
        val updates = Fixtures.events("updateMonitorIntoList").map { (it as KumaEvent.MonitorUpdate).monitors }
        assertTrue(updates.all { it.keys == setOf(18L) })
        assertTrue(updates.first().getValue(18).active)
        assertFalse(updates.last().getValue(18).active)
    }

    @Test
    fun `deleteMonitorFromList carries a numeric id`() {
        assertEquals(
            listOf(KumaEvent.MonitorDeleted(18), KumaEvent.MonitorDeleted(18)),
            Fixtures.events("deleteMonitorFromList"),
        )
    }

    @Test
    fun `heartbeatList rows are snake_case, oldest first, keyed by string monitor id`() {
        val lists = Fixtures.events("heartbeatList").map { it as KumaEvent.HeartbeatList }
        assertEquals((1L..13L).toList(), lists.map { it.monitorId })
        lists.forEach { list ->
            assertFalse(list.overwrite)
            val beats = list.beats.map { requireNotNull(it.toModel()) }
            assertTrue(beats.all { it.monitorId == list.monitorId })
            assertEquals(beats.sortedBy { it.timeMillis }, beats, "oldest first")
        }
        val first = lists.first().beats.first()
        assertEquals(1L, first.monitorId)
        assertEquals(0, first.downCount)
        val beat = requireNotNull(first.toModel())
        assertEquals(Instant.parse("2026-10-28T22:25:47.633Z").toEpochMilli(), beat.timeMillis)
        assertEquals(MonitorStatus.UP, beat.status)
        assertTrue(beat.important, "important arrives as 1")
        assertEquals(97.0, beat.pingMs)
        assertEquals(17.7, lists[4].beats.first().ping, "ping monitor reports fractional ms")
        assertNull(lists[6].beats.first().ping, "push beat without ping")
    }

    @Test
    fun `heartbeat events are camelCase with optional ping and duration`() {
        val beats = Fixtures.events("heartbeat").map { (it as KumaEvent.NewHeartbeat).beat }
        val models = beats.map { requireNotNull(it.toModel()) }
        assertEquals(beats.size, models.size)

        val maintenance = models.first { it.status == MonitorStatus.MAINTENANCE }
        assertEquals(10L, maintenance.monitorId)
        assertNull(maintenance.pingMs, "maintenance beats have no ping")

        val push = beats.first { it.monitorId == 7L && it.status == 0 }
        assertEquals(20, push.duration)
        assertNull(beats.first { it.monitorId == 1L }.duration)

        val important = models.filter { it.important }
        assertTrue(important.isNotEmpty())
        assertEquals(
            Instant.parse("2026-10-28T22:40:07.716Z").toEpochMilli(),
            models.first().timeMillis,
        )
    }

    @Test
    fun `uptime periods are 24, 720 and the string 1y`() {
        val uptimes = Fixtures.events("uptime").map { it as KumaEvent.Uptime }
        assertEquals(
            setOf(UptimePeriod.DAY, UptimePeriod.MONTH, UptimePeriod.YEAR),
            uptimes.map { it.period }.toSet(),
        )
        assertTrue(uptimes.all { it.fraction in 0.0..1.0 })
        assertEquals(KumaEvent.Uptime(1, UptimePeriod.YEAR, 1.0), uptimes.first { it.period == UptimePeriod.YEAR })
    }

    @Test
    fun `avgPing has string id and nullable number`() {
        val pings = Fixtures.events("avgPing").map { it as KumaEvent.AvgPing }
        assertEquals(KumaEvent.AvgPing(1, 205.84), pings.first())
        assertTrue(pings.any { it.monitorId == 18L })
    }

    @Test
    fun `certInfo is a JSON string that parses into the leaf certificate`() {
        val certs = Fixtures.events("certInfo").map { it as KumaEvent.Cert }
        assertTrue(certs.isNotEmpty())
        val first = certs.first()
        assertEquals(1L, first.monitorId)
        assertNotNull(first.payload.certInfo?.issuerCertificate, "chain is nested")
        val cert = first.payload.toModel(first.monitorId)
        assertTrue(cert.valid)
        assertEquals("example.com", cert.subject)
        assertEquals("Cloudflare TLS Issuing ECC CA 3", cert.issuer)
        assertEquals(58, cert.daysRemaining)
        assertEquals(Instant.parse("2026-12-25T22:56:35Z").toEpochMilli(), cert.validToMillis)
        assertEquals(Instant.parse("2026-09-26T22:49:11Z").toEpochMilli(), cert.validFromMillis)
        assertTrue(cert.fingerprint256.orEmpty().startsWith("85:CA:6A"))
        certs.forEach { assertNotNull(it.payload.toModel(it.monitorId).subject) }
    }

    @Test
    fun `maintenanceList is keyed by id and reports status`() {
        val lists = Fixtures.events("maintenanceList").map { (it as KumaEvent.MaintenanceList).maintenances }
        val maintenance = lists.first().getValue(1)
        assertEquals("Dev maintenance (single)", maintenance.title)
        assertTrue(maintenance.active)
        assertTrue(maintenance.isUnderMaintenance)
    }

    @Test
    fun `notificationList parses config strings into provider fields`() {
        val list = (Fixtures.events("notificationList").first() as KumaEvent.NotificationList).notifications
        val models = list.map { it.toModel() }
        val webhook = models.first { it.type == "webhook" }
        assertEquals(1L, webhook.id)
        assertEquals("Dev webhook", webhook.name)
        assertTrue(webhook.active)
        assertFalse(webhook.isDefault)
        assertEquals("http://ntfy:80/upTESTTOPIC-webhook", webhook.fields["webhookURL"])
        assertEquals("POST", webhook.fields["httpMethod"])
        assertFalse("name" in webhook.fields || "type" in webhook.fields || "applyExisting" in webhook.fields)

        val ntfy = models.first { it.type == "ntfy" }
        assertEquals(5L, ntfy.fields["ntfyPriority"])
        assertEquals(false, ntfy.fields["ntfyUseTemplate"])
    }
}
