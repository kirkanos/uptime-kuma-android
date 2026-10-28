package net.kirkanos.uptimekuma.feature.monitors.dashboard

import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorTag

/** User-controlled list options of the dashboard. */
data class DashboardFilter(
    val query: String = "",
    /** Empty = all statuses. */
    val statuses: Set<DisplayStatus> = emptySet(),
    /** Empty = all tags; otherwise a monitor needs at least one of these tags. */
    val tagIds: Set<Long> = emptySet(),
    /** Sort down/pending monitors (and groups containing them) to the top. */
    val problemsFirst: Boolean = false,
    val collapsedGroups: Set<Long> = emptySet(),
) {
    val isFiltering: Boolean get() = query.isNotBlank() || statuses.isNotEmpty() || tagIds.isNotEmpty()
}

/** One visible line of the dashboard list. */
data class MonitorRow(
    val overview: MonitorOverview,
    /** Nesting level, 0 for top-level monitors. */
    val depth: Int,
    /** Number of direct children (0 for non-groups). */
    val childCount: Int,
    val expanded: Boolean,
    /** Worst status of this monitor and all its descendants. */
    val worstStatus: DisplayStatus,
) {
    val id: Long get() = overview.monitor.id
}

/** Number of monitors per [DisplayStatus]. */
data class StatusCounts(
    private val counts: Map<DisplayStatus, Int> = emptyMap(),
) {
    operator fun get(status: DisplayStatus): Int = counts[status] ?: 0

    val total: Int get() = counts.values.sum()
}

/** Pure list logic of the dashboard: grouping, filtering, search and sorting. */
object MonitorList {
    /** Lower = more severe; used for "problems first" sorting and group roll-up. */
    private val severity =
        mapOf(
            DisplayStatus.DOWN to 0,
            DisplayStatus.PENDING to 1,
            DisplayStatus.UNKNOWN to 2,
            DisplayStatus.MAINTENANCE to 3,
            DisplayStatus.UP to 4,
            DisplayStatus.PAUSED to 5,
        )

    fun severityOf(status: DisplayStatus): Int = severity.getValue(status)

    fun counts(monitors: List<MonitorOverview>): StatusCounts =
        StatusCounts(monitors.groupingBy { it.displayStatus }.eachCount())

    /** Distinct tags (by tag id, ignoring values) used by any monitor, sorted by name. */
    fun tags(monitors: List<MonitorOverview>): List<MonitorTag> =
        monitors
            .asSequence()
            .flatMap { it.monitor.tags }
            .distinctBy { it.tagId }
            .map { it.copy(value = "") }
            .sortedBy { it.name.lowercase() }
            .toList()

    fun matchesQuery(
        overview: MonitorOverview,
        query: String,
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val monitor = overview.monitor
        return monitor.name.contains(q, ignoreCase = true) ||
            monitor.target?.contains(q, ignoreCase = true) == true ||
            monitor.description?.contains(q, ignoreCase = true) == true ||
            monitor.tags.any { it.name.contains(q, ignoreCase = true) || it.value.contains(q, ignoreCase = true) }
    }

    private fun matchesFilters(
        overview: MonitorOverview,
        filter: DashboardFilter,
    ): Boolean {
        val statusOk = filter.statuses.isEmpty() || overview.displayStatus in filter.statuses
        val tagOk = filter.tagIds.isEmpty() || overview.monitor.tags.any { it.tagId in filter.tagIds }
        return statusOk && tagOk
    }

    /**
     * Builds the visible rows: groups followed by their (indented) children in repository order.
     * With filters, a monitor is shown when it matches or one of its descendants does; children of a
     * group whose name matches the search inherit the match. A search expands collapsed groups.
     */
    fun rows(
        monitors: List<MonitorOverview>,
        filter: DashboardFilter,
    ): List<MonitorRow> {
        val byId = monitors.associateBy { it.monitor.id }
        val children = HashMap<Long, MutableList<MonitorOverview>>()
        val roots = ArrayList<MonitorOverview>()
        monitors.forEach { overview ->
            val parentId = overview.monitor.parentId
            if (parentId != null && parentId in byId && parentId != overview.monitor.id) {
                children.getOrPut(parentId) { ArrayList() } += overview
            } else {
                roots += overview
            }
        }
        val worst = HashMap<Long, DisplayStatus>()

        fun worstOf(
            overview: MonitorOverview,
            path: Set<Long>,
        ): DisplayStatus =
            worst.getOrPut(overview.monitor.id) {
                val own = overview.displayStatus
                children[overview.monitor.id]
                    .orEmpty()
                    .filter { it.monitor.id !in path }
                    .map { worstOf(it, path + it.monitor.id) }
                    .plus(own)
                    .minBy { severityOf(it) }
            }

        fun sorted(list: List<MonitorOverview>): List<MonitorOverview> =
            if (filter.problemsFirst) {
                list.sortedBy { severityOf(worstOf(it, setOf(it.monitor.id))) }
            } else {
                list
            }

        val searching = filter.query.isNotBlank()

        fun visit(
            overview: MonitorOverview,
            depth: Int,
            inheritedQueryMatch: Boolean,
            path: Set<Long>,
        ): List<MonitorRow> {
            val id = overview.monitor.id
            val ownQueryMatch = inheritedQueryMatch || matchesQuery(overview, filter.query)
            val selfVisible = ownQueryMatch && matchesFilters(overview, filter)
            val directChildren = children[id].orEmpty().filter { it.monitor.id !in path }
            val childRows =
                sorted(directChildren).flatMap {
                    visit(it, depth + 1, searching && ownQueryMatch, path + it.monitor.id)
                }
            if (!selfVisible && childRows.isEmpty()) return emptyList()
            val expanded = searching || id !in filter.collapsedGroups
            val row =
                MonitorRow(
                    overview = overview,
                    depth = depth,
                    childCount = directChildren.size,
                    expanded = expanded,
                    worstStatus = worstOf(overview, path),
                )
            return if (expanded) listOf(row) + childRows else listOf(row)
        }

        return sorted(roots).flatMap { visit(it, 0, false, setOf(it.monitor.id)) }
    }
}
