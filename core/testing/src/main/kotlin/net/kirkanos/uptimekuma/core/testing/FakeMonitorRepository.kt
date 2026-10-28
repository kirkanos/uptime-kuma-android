package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.Tag

/**
 * In-memory [MonitorRepository]. Mutations (pause/resume/delete) update [monitors] like the real cache would.
 * Set [error] to make every suspend call fail with that exception.
 */
class FakeMonitorRepository(
    initial: List<MonitorOverview> = emptyList(),
    initialTags: List<Tag> = emptyList(),
) : MonitorRepository {
    val monitors = MutableStateFlow(initial)
    val tags = MutableStateFlow(initialTags)

    var chartProvider: (monitorId: Long, periodHours: Int) -> List<ChartPoint> = { _, _ -> emptyList() }
    var beatsProvider: (monitorId: Long, hours: Int) -> List<Heartbeat> = { id, _ ->
        monitors.value
            .firstOrNull { it.monitor.id == id }
            ?.recentBeats
            .orEmpty()
    }

    /** Important events of all monitors, newest first; [loadImportantEvents] pages through them. */
    var importantEvents: List<Heartbeat> = emptyList()

    var error: KumaException? = null

    /** Artificial latency for suspend calls, e.g. to show progress UI in a demo build. */
    var delayMillis: Long = 0

    val pausedIds = mutableListOf<Long>()
    val resumedIds = mutableListOf<Long>()
    val deletedIds = mutableListOf<Long>()
    val chartRequests = mutableListOf<Pair<Long, Int>>()
    val eventRequests = mutableListOf<Triple<Long?, Int, Int>>()

    override fun observeMonitors(): Flow<List<MonitorOverview>> = monitors

    override fun observeMonitor(id: Long): Flow<MonitorOverview?> =
        monitors.map { list -> list.firstOrNull { it.monitor.id == id } }.distinctUntilChanged()

    override suspend fun loadBeats(
        id: Long,
        hours: Int,
    ): List<Heartbeat> {
        simulate()
        return beatsProvider(id, hours)
    }

    override suspend fun loadChart(
        id: Long,
        periodHours: Int,
    ): List<ChartPoint> {
        chartRequests += id to periodHours
        simulate()
        return chartProvider(id, periodHours)
    }

    override suspend fun loadImportantEvents(
        monitorId: Long?,
        offset: Int,
        count: Int,
    ): List<Heartbeat> {
        eventRequests += Triple(monitorId, offset, count)
        simulate()
        return importantEvents
            .filter { monitorId == null || it.monitorId == monitorId }
            .drop(offset)
            .take(count)
    }

    override suspend fun pause(id: Long) {
        simulate()
        pausedIds += id
        setActive(id, false)
    }

    override suspend fun resume(id: Long) {
        simulate()
        resumedIds += id
        setActive(id, true)
    }

    override suspend fun delete(id: Long) {
        simulate()
        deletedIds += id
        monitors.value = monitors.value.filterNot { it.monitor.id == id }
    }

    override fun observeTags(): Flow<List<Tag>> = tags

    private fun setActive(
        id: Long,
        active: Boolean,
    ) {
        monitors.value =
            monitors.value.map {
                if (it.monitor.id == id) it.copy(monitor = it.monitor.copy(active = active)) else it
            }
    }

    private suspend fun simulate() {
        if (delayMillis > 0) delay(delayMillis)
        error?.let { throw it }
    }

    companion object {
        /** A repository pre-filled with [SampleData], with charts and important events. */
        fun withSampleData(nowMillis: Long = System.currentTimeMillis()): FakeMonitorRepository =
            FakeMonitorRepository(SampleData.monitors(nowMillis), SampleData.tags).apply {
                chartProvider = { id, hours -> SampleData.chart(id, hours) }
                importantEvents = SampleData.importantEvents(nowMillis)
            }
    }
}
