package net.kirkanos.uptimekuma.core.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kirkanos.uptimekuma.core.kumaclient.KumaApi
import net.kirkanos.uptimekuma.core.kumaclient.KumaEvent
import net.kirkanos.uptimekuma.core.kumaclient.dto.MaintenanceDto
import net.kirkanos.uptimekuma.core.model.KumaException

/** Applies server events of a logged-in socket to the [KumaCache]. */
internal class KumaEventSync(
    private val cache: KumaCache,
) {
    /** True for events that carry user data and must only be applied after authentication. */
    fun isDataEvent(event: KumaEvent): Boolean =
        when (event) {
            is KumaEvent.Info,
            KumaEvent.Setup,
            KumaEvent.LoginRequired,
            KumaEvent.AutoLogin,
            KumaEvent.Refresh,
            -> false

            else -> true
        }

    /** [api] is used to resolve which monitors an active maintenance covers. */
    suspend fun apply(
        event: KumaEvent,
        api: KumaApi?,
    ) {
        when (event) {
            is KumaEvent.MonitorList -> {
                cache.replaceMonitors(event.monitors.values)
            }

            is KumaEvent.MonitorUpdate -> {
                cache.upsertMonitors(event.monitors.values)
            }

            is KumaEvent.MonitorDeleted -> {
                cache.deleteMonitor(event.monitorId)
            }

            is KumaEvent.HeartbeatList -> {
                cache.putHeartbeats(
                    monitorId = event.monitorId,
                    beats = event.beats.mapNotNull { it.toModel(event.monitorId) },
                    replace = event.overwrite,
                )
            }

            is KumaEvent.NewHeartbeat -> {
                event.beat.toModel()?.let { cache.addHeartbeat(it) }
            }

            is KumaEvent.Uptime -> {
                cache.putUptime(event.monitorId, event.period, event.fraction)
            }

            is KumaEvent.AvgPing -> {
                cache.putAvgPing(event.monitorId, event.avgPingMs)
            }

            is KumaEvent.Cert -> {
                cache.putCert(event.payload.toModel(event.monitorId))
            }

            is KumaEvent.MaintenanceList -> {
                applyMaintenance(event.maintenances.values, api)
            }

            is KumaEvent.NotificationList -> {
                cache.replaceNotifications(event.notifications)
            }

            is KumaEvent.Info,
            KumaEvent.Setup,
            KumaEvent.LoginRequired,
            KumaEvent.AutoLogin,
            KumaEvent.Refresh,
            -> {}
        }
    }

    /**
     * `maintenanceList` does not say which monitors are affected, so ask `getMonitorMaintenance` for
     * every maintenance that is currently active and expand to child monitors. If that fails the
     * previous set (seeded from `MonitorJSON.maintenance`) is kept; status-3 heartbeats still show
     * maintenance in the UI.
     */
    private suspend fun applyMaintenance(
        maintenances: Collection<MaintenanceDto>,
        api: KumaApi?,
    ) {
        val active = maintenances.filter { it.isUnderMaintenance }
        val direct =
            try {
                active.flatMap { api?.getMonitorMaintenance(it.id) ?: return }.toSet()
            } catch (_: KumaException) {
                return
            }
        cache.replaceMaintenanceMonitors(withDescendants(direct, cache.monitorParents()))
    }

    companion object {
        /** [roots] plus every monitor whose ancestor chain (via [parents]) contains one of them. */
        fun withDescendants(
            roots: Set<Long>,
            parents: Map<Long, Long?>,
        ): Set<Long> {
            if (roots.isEmpty()) return roots
            return roots + parents.keys.filter { id -> ancestors(id, parents).any { it in roots } }
        }

        /** Parent chain of [id], guarded against cycles. */
        fun ancestors(
            id: Long,
            parents: Map<Long, Long?>,
        ): Sequence<Long> {
            val seen = mutableSetOf(id)
            return generateSequence(parents[id]) { parent -> parents[parent] }.takeWhile { seen.add(it) }
        }
    }
}

/**
 * Tracks whether the initial post-login burst is complete: `monitorList` received and a
 * `heartbeatList` for every listed monitor. Reset on every (re)connect.
 */
internal class SyncTracker {
    private var expected: Set<Long>? = null
    private val received = mutableSetOf<Long>()
    private val mutableComplete = MutableStateFlow(false)
    val complete: StateFlow<Boolean> = mutableComplete.asStateFlow()

    @Synchronized
    fun reset() {
        expected = null
        received.clear()
        mutableComplete.value = false
    }

    @Synchronized
    fun onEvent(event: KumaEvent) {
        when (event) {
            is KumaEvent.MonitorList -> expected = event.monitors.keys
            is KumaEvent.HeartbeatList -> received += event.monitorId
            is KumaEvent.MonitorDeleted -> expected = expected?.minus(event.monitorId)
            else -> return
        }
        val target = expected
        mutableComplete.value = target != null && received.containsAll(target)
    }
}
