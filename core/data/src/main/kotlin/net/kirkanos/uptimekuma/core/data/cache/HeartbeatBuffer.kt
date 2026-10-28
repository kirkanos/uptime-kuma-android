package net.kirkanos.uptimekuma.core.data.cache

import net.kirkanos.uptimekuma.core.model.Heartbeat

/**
 * Heartbeat retention rules of the local cache. The Room implementation expresses the same rule in
 * SQL ([net.kirkanos.uptimekuma.core.data.db.HeartbeatDao.trim]); this pure version is the
 * reference used by tests and the in-memory cache.
 */
object HeartbeatBuffer {
    /** Newest beats kept per monitor (the web UI keeps 150 in memory). */
    const val KEEP_RECENT = 150

    /** Newest important beats kept per monitor in addition to [KEEP_RECENT]. */
    const val KEEP_IMPORTANT = 500

    /** Beats exposed in `MonitorOverview.recentBeats`. */
    const val OVERVIEW_BEATS = 100

    /**
     * Merges [incoming] into [existing] (same monitor). With [replace] (`heartbeatList` overwrite)
     * the existing beats are dropped. Beats are keyed by time; incoming wins. Result is oldest first
     * and trimmed by [retain].
     */
    fun merge(
        existing: List<Heartbeat>,
        incoming: List<Heartbeat>,
        replace: Boolean,
        keepRecent: Int = KEEP_RECENT,
        keepImportant: Int = KEEP_IMPORTANT,
    ): List<Heartbeat> {
        val byTime = LinkedHashMap<Long, Heartbeat>()
        if (!replace) existing.forEach { byTime[it.timeMillis] = it }
        incoming.forEach { byTime[it.timeMillis] = it }
        return retain(byTime.values.sortedBy { it.timeMillis }, keepRecent, keepImportant)
    }

    /** Keeps the newest [keepRecent] beats plus the newest [keepImportant] important ones; oldest first. */
    fun retain(
        beats: List<Heartbeat>,
        keepRecent: Int = KEEP_RECENT,
        keepImportant: Int = KEEP_IMPORTANT,
    ): List<Heartbeat> {
        val sorted = beats.sortedBy { it.timeMillis }
        val recent = sorted.takeLast(keepRecent).mapTo(HashSet()) { it.timeMillis }
        val important = sorted.filter { it.important }.takeLast(keepImportant).mapTo(HashSet()) { it.timeMillis }
        return sorted.filter { it.timeMillis in recent || it.timeMillis in important }
    }
}
