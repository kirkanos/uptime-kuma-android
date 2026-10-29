package net.kirkanos.uptimekuma.widget.state

import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorOverview

/** Overall status shown by the small widget and the Quick Settings tile. */
enum class OverallStatus {
    NOT_CONFIGURED,

    /** Configured, but no active (non-paused) monitor with a known state. */
    NO_MONITORS,

    /** Nothing down or pending (maintenance counts as fine). */
    ALL_UP,

    /** At least one monitor pending, none down. */
    PENDING,

    /** At least one monitor down. */
    DOWN,
}

/** One monitor row of the large widget. */
data class WidgetMonitorRow(
    val id: Long,
    val name: String,
    val status: DisplayStatus,
    val lastBeatMillis: Long?,
    val pingMs: Double?,
)

/** Everything the widget and the tile render; produced by [WidgetStateMapper]. */
data class MonitorsWidgetState(
    val overall: OverallStatus,
    val up: Int = 0,
    val down: Int = 0,
    val pending: Int = 0,
    val maintenance: Int = 0,
    val paused: Int = 0,
    /** Problem monitors first (down, pending, maintenance), then up ones; paused / unknown are left out. */
    val rows: List<WidgetMonitorRow> = emptyList(),
    /** Time of the newest heartbeat in the cache: how fresh the shown data is. */
    val updatedAtMillis: Long? = null,
) {
    /** Monitors with a known, active state. */
    val active: Int get() = up + down + pending + maintenance

    /**
     * The part of the state whose change must reach the home screen right away: statuses and names.
     * Ping values and beat times alone are refreshed lazily (see [WidgetUpdatePolicy]).
     */
    fun significant(): MonitorsWidgetState =
        copy(rows = rows.map { it.copy(lastBeatMillis = null, pingMs = null) }, updatedAtMillis = null)

    companion object {
        val NotConfigured = MonitorsWidgetState(overall = OverallStatus.NOT_CONFIGURED)
    }
}

/** Pure mapping from the monitor cache to [MonitorsWidgetState]. */
object WidgetStateMapper {
    private val problemOrder = listOf(DisplayStatus.DOWN, DisplayStatus.PENDING, DisplayStatus.MAINTENANCE)

    /**
     * Groups are left out (their children are listed and would count twice). Within each status bucket
     * Kuma's order (weight, name) of [monitors] is kept.
     */
    fun map(
        configured: Boolean,
        monitors: List<MonitorOverview>,
    ): MonitorsWidgetState {
        if (!configured) return MonitorsWidgetState.NotConfigured
        val plain = monitors.filterNot { it.monitor.isGroup }
        val byStatus = plain.groupBy { it.displayStatus }

        fun count(status: DisplayStatus) = byStatus[status].orEmpty().size
        val down = count(DisplayStatus.DOWN)
        val pending = count(DisplayStatus.PENDING)
        val up = count(DisplayStatus.UP)
        val maintenance = count(DisplayStatus.MAINTENANCE)
        val overall =
            when {
                down > 0 -> OverallStatus.DOWN
                pending > 0 -> OverallStatus.PENDING
                up + maintenance == 0 -> OverallStatus.NO_MONITORS
                else -> OverallStatus.ALL_UP
            }
        val rows =
            (problemOrder + DisplayStatus.UP).flatMap { status ->
                byStatus[status].orEmpty().map { it.toRow(status) }
            }
        return MonitorsWidgetState(
            overall = overall,
            up = up,
            down = down,
            pending = pending,
            maintenance = maintenance,
            paused = count(DisplayStatus.PAUSED),
            rows = rows,
            updatedAtMillis = plain.mapNotNull { it.lastBeat?.timeMillis }.maxOrNull(),
        )
    }

    private fun MonitorOverview.toRow(status: DisplayStatus): WidgetMonitorRow {
        val beat = lastBeat
        return WidgetMonitorRow(
            id = monitor.id,
            name = monitor.pathName.ifBlank { monitor.name },
            status = status,
            lastBeatMillis = beat?.timeMillis,
            pingMs = beat?.pingMs,
        )
    }
}

/**
 * Decides whether a new state is pushed to the home screen: immediately when a status or name changed,
 * otherwise (only ping values / beat times differ) at most every [minIntervalMillis]. Keeps the widget
 * from redrawing on every heartbeat.
 */
class WidgetUpdatePolicy(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
) {
    fun shouldPublish(
        published: MonitorsWidgetState?,
        next: MonitorsWidgetState,
        publishedAtMillis: Long,
        nowMillis: Long,
    ): Boolean =
        when {
            published == null -> true
            published == next -> false
            published.significant() != next.significant() -> true
            else -> nowMillis - publishedAtMillis >= minIntervalMillis
        }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS: Long = 5 * 60_000L
    }
}
