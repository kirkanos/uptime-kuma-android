package net.kirkanos.uptimekuma.feature.monitors.dashboard

import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorListTest {
    private val prod = MonitorTag(1, "env", "#059669", "prod")
    private val team = MonitorTag(2, "team", "#7C3AED", "backend")

    private fun overview(
        id: Long,
        name: String,
        status: MonitorStatus? = MonitorStatus.UP,
        parentId: Long? = null,
        type: String = "http",
        active: Boolean = true,
        tags: List<MonitorTag> = emptyList(),
    ) = MonitorOverview(
        monitor =
            Monitor(
                id = id,
                name = name,
                type = type,
                url = if (type == Monitor.TYPE_GROUP) null else "https://$name.example.com",
                parentId = parentId,
                active = active,
                tags = tags,
            ),
        status = status,
    )

    private val group = overview(10, "cluster", type = Monitor.TYPE_GROUP)
    private val childUp = overview(11, "node-a", parentId = 10, tags = listOf(prod))
    private val childDown = overview(12, "node-b", status = MonitorStatus.DOWN, parentId = 10)
    private val website = overview(1, "website", tags = listOf(prod, team))
    private val paused = overview(2, "staging", active = false)
    private val pending = overview(3, "auth", status = MonitorStatus.PENDING, tags = listOf(team))
    private val all = listOf(website, group, childUp, childDown, paused, pending)

    private fun List<MonitorRow>.ids() = map { it.id }

    @Test
    fun `groups are followed by indented children in repository order`() {
        val rows = MonitorList.rows(all, DashboardFilter())
        assertEquals(listOf(1L, 10L, 11L, 12L, 2L, 3L), rows.ids())
        assertEquals(listOf(0, 0, 1, 1, 0, 0), rows.map { it.depth })
        assertEquals(2, rows.first { it.id == 10L }.childCount)
    }

    @Test
    fun `group rolls up the worst child status`() {
        val rows = MonitorList.rows(all, DashboardFilter())
        assertEquals(DisplayStatus.DOWN, rows.first { it.id == 10L }.worstStatus)
        assertEquals(DisplayStatus.UP, rows.first { it.id == 11L }.worstStatus)
    }

    @Test
    fun `collapsed group hides its children`() {
        val rows = MonitorList.rows(all, DashboardFilter(collapsedGroups = setOf(10L)))
        assertEquals(listOf(1L, 10L, 2L, 3L), rows.ids())
        assertFalse(rows.first { it.id == 10L }.expanded)
    }

    @Test
    fun `problems first sorts by severity and keeps children under their group`() {
        val rows = MonitorList.rows(all, DashboardFilter(problemsFirst = true))
        assertEquals(listOf(10L, 12L, 11L, 3L, 1L, 2L), rows.ids())
    }

    @Test
    fun `status filter keeps ancestors of matching children`() {
        val rows = MonitorList.rows(all, DashboardFilter(statuses = setOf(DisplayStatus.DOWN)))
        assertEquals(listOf(10L, 12L), rows.ids())
    }

    @Test
    fun `paused filter uses display status`() {
        val rows = MonitorList.rows(all, DashboardFilter(statuses = setOf(DisplayStatus.PAUSED)))
        assertEquals(listOf(2L), rows.ids())
    }

    @Test
    fun `multiple statuses are combined with or`() {
        val rows =
            MonitorList.rows(all, DashboardFilter(statuses = setOf(DisplayStatus.PENDING, DisplayStatus.PAUSED)))
        assertEquals(listOf(2L, 3L), rows.ids())
    }

    @Test
    fun `tag filter matches any selected tag`() {
        assertEquals(listOf(1L, 10L, 11L), MonitorList.rows(all, DashboardFilter(tagIds = setOf(1L))).ids())
        assertEquals(listOf(1L, 3L), MonitorList.rows(all, DashboardFilter(tagIds = setOf(2L))).ids())
    }

    @Test
    fun `status and tag filters are combined with and`() {
        val rows =
            MonitorList.rows(all, DashboardFilter(statuses = setOf(DisplayStatus.UP), tagIds = setOf(2L)))
        assertEquals(listOf(1L), rows.ids())
    }

    @Test
    fun `search matches name, target and tag case-insensitively`() {
        assertEquals(listOf(1L), MonitorList.rows(all, DashboardFilter(query = "WEB")).ids())
        assertEquals(listOf(10L, 12L), MonitorList.rows(all, DashboardFilter(query = "node-b.example")).ids())
        assertEquals(listOf(1L, 3L), MonitorList.rows(all, DashboardFilter(query = "backend")).ids())
    }

    @Test
    fun `search on group name shows its children and expands collapsed groups`() {
        val rows = MonitorList.rows(all, DashboardFilter(query = "cluster", collapsedGroups = setOf(10L)))
        assertEquals(listOf(10L, 11L, 12L), rows.ids())
        assertTrue(rows.first().expanded)
    }

    @Test
    fun `orphans with unknown parent are shown at top level`() {
        val orphan = overview(20, "orphan", parentId = 99)
        assertEquals(listOf(20L), MonitorList.rows(listOf(orphan), DashboardFilter()).ids())
    }

    @Test
    fun `parent cycles do not loop`() {
        val a = overview(30, "a", parentId = 31, type = Monitor.TYPE_GROUP)
        val b = overview(31, "b", parentId = 30, type = Monitor.TYPE_GROUP)
        val c = overview(32, "c")
        assertEquals(listOf(32L), MonitorList.rows(listOf(a, b, c), DashboardFilter()).ids())
    }

    @Test
    fun `counts and distinct tags`() {
        val counts = MonitorList.counts(all)
        assertEquals(3, counts[DisplayStatus.UP])
        assertEquals(1, counts[DisplayStatus.DOWN])
        assertEquals(1, counts[DisplayStatus.PENDING])
        assertEquals(1, counts[DisplayStatus.PAUSED])
        assertEquals(0, counts[DisplayStatus.MAINTENANCE])
        assertEquals(6, counts.total)
        assertEquals(listOf("env", "team"), MonitorList.tags(all).map { it.name })
        assertTrue(MonitorList.tags(all).all { it.value.isEmpty() })
    }

    @Test
    fun `sample data renders every monitor once`() {
        val sample = SampleData.monitors(1_761_700_000_000L)
        val rows = MonitorList.rows(sample, DashboardFilter())
        assertEquals(sample.map { it.monitor.id }.toSet(), rows.ids().toSet())
        assertEquals(sample.size, rows.size)
    }
}
