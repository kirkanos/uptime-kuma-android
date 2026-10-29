package net.kirkanos.uptimekuma.core.data.cache

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.data.db.HeartbeatEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorOverviewSourceTest {
    private fun monitor(id: Long) =
        MonitorEntity(
            id = id,
            name = "Monitor $id",
            type = "http",
            description = null,
            url = null,
            hostname = null,
            port = null,
            active = true,
            forceInactive = false,
            parentId = null,
            childrenIds = "",
            pathName = "Monitor $id",
            intervalSeconds = 20,
            notificationIds = "",
            expiryNotification = false,
            weight = 0,
        )

    private fun beat(
        monitorId: Long,
        time: Long,
    ) = HeartbeatEntity(
        monitorId,
        time,
        status = 1,
        message = "",
        pingMs = 1.0,
        important = false,
        durationSeconds = null,
        retries = 0,
    )

    @Test
    fun `a burst of cache writes is throttled to the first and the latest state`() =
        runTest {
            val beats = MutableStateFlow(listOf(beat(1, 0)))
            val flow =
                MonitorOverviewSource.overviewFlow(
                    monitors = flowOf(listOf(monitor(1))),
                    tags = flowOf(emptyList()),
                    beats = beats,
                    uptime = flowOf(emptyList()),
                    certs = flowOf(emptyList()),
                    maintenance = flowOf(emptyList()),
                    throttleMillis = 250,
                )
            val emitted = mutableListOf<Int>()
            val job = launch { flow.collect { list -> emitted += list.single().recentBeats.size } }
            runCurrent()
            assertEquals(listOf(1), emitted, "the first state is delivered without delay")

            // 50 heartbeats within 100 ms, like the login burst of a large server.
            repeat(50) { i ->
                beats.value = beats.value + beat(1, i + 1L)
                advanceTimeBy(2)
            }
            runCurrent()
            assertEquals(1, emitted.size, "nothing more inside the throttle window")

            advanceUntilIdle()
            assertEquals(51, emitted.last(), "the latest state always arrives")
            assertTrue(emitted.size <= 3, "burst collapsed into few emissions: $emitted")
            job.cancel()
        }

    @Test
    fun `throttle passes every value of a slow stream`() =
        runTest {
            val values = MutableStateFlow(0)
            val received = mutableListOf<Int>()
            val job = launch { with(MonitorOverviewSource) { values.throttle(100) }.toList(received) }
            runCurrent()
            repeat(3) {
                advanceTimeBy(1_000)
                values.value = it + 1
                runCurrent()
            }
            assertEquals(listOf(0, 1, 2, 3), received)
            job.cancel()
        }
}
