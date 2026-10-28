package net.kirkanos.uptimekuma.core.data

import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.data.cache.KumaEventSync
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Replays the recorded Kuma 2.5.8 event stream into the cache. */
class EventSyncFixtureTest {
    private val cache = FakeKumaCache()
    private val api = FakeKumaApi("http://kuma")
    private val sync = KumaEventSync(cache)

    private suspend fun replay(events: List<KumaEvent>) = events.forEach { sync.apply(it, api) }

    @Test
    fun `login burst fills monitors, beats, stats, certs, notifications and maintenance`() =
        runTest {
            replay(Fixtures.loginBurst())

            assertEquals((1L..13L).toSet(), cache.monitors.keys)
            assertEquals((1L..13L).toSet(), cache.beats.keys)
            assertTrue(cache.beats.getValue(7).size > 40)
            assertTrue(cache.beats.values.all { list -> list == list.sortedBy { it.timeMillis } })
            val lastPing = Fixtures.events("avgPing").filterIsInstance<KumaEvent.AvgPing>().last { it.monitorId == 1L }
            assertEquals(lastPing.avgPingMs, cache.uptime.getValue(1).avgPingMs)
            assertEquals(1.0, cache.uptime.getValue(1).uptime24h)
            assertEquals(1.0, cache.uptime.getValue(1).uptime1y)
            assertEquals("example.com", cache.certs.getValue(1).subject)
            assertEquals(setOf(1L, 2L), cache.notifications.keys)
            assertEquals(setOf(10L), cache.maintenance, "resolved via getMonitorMaintenance")
            assertTrue("getMonitorMaintenance:1" in api.calls)
        }

    @Test
    fun `live heartbeats append, monitor updates and deletes apply`() =
        runTest {
            replay(Fixtures.loginBurst())
            val before = cache.beats.getValue(10).size
            replay(Fixtures.events("heartbeat"))
            val maintenanceBeats = cache.beats.getValue(10)
            assertEquals(before + 3, maintenanceBeats.size)
            assertEquals(MonitorStatus.MAINTENANCE, maintenanceBeats.last().status)

            replay(Fixtures.events("updateMonitorIntoList"))
            assertFalse(cache.monitors.getValue(18).active, "last update paused monitor 18")
            assertTrue(18L in cache.beats.keys, "heartbeats of 18 arrived before")

            replay(Fixtures.events("deleteMonitorFromList"))
            assertFalse(18L in cache.monitors.keys)
            assertFalse(18L in cache.beats.keys)
        }

    @Test
    fun `heartbeatList overwrite replaces, otherwise merges`() =
        runTest {
            replay(Fixtures.loginBurst())
            val list = Fixtures.events("heartbeatList").first() as KumaEvent.HeartbeatList
            sync.apply(list, api)
            assertEquals(
                list.beats.size,
                cache.beats.getValue(1).size,
                "re-sent list after reconnect merges without duplicates",
            )
            sync.apply(KumaEvent.HeartbeatList(1, emptyList(), overwrite = true), api)
            assertEquals(0, cache.beats.getValue(1).size, "clearHeartbeats")
        }

    @Test
    fun `maintenance list with nothing active clears the set without requests`() =
        runTest {
            replay(Fixtures.loginBurst())
            val calls = api.calls.size
            sync.apply(KumaEvent.MaintenanceList(emptyMap()), api)
            assertEquals(emptySet<Long>(), cache.maintenance)
            assertEquals(calls, api.calls.size)
        }

    @Test
    fun `monitor list replacement drops monitors that disappeared`() =
        runTest {
            replay(Fixtures.loginBurst())
            val list = (Fixtures.first("monitorList") as KumaEvent.MonitorList).monitors
            sync.apply(KumaEvent.MonitorList(list - 9L), api)
            assertFalse(9L in cache.monitors.keys)
            assertFalse(9L in cache.beats.keys)
            assertNotNull(cache.beats[1])
            assertEquals(setOf(10L), cache.maintenance, "seeded from MonitorJSON.maintenance")
        }
}
