package net.kirkanos.uptimekuma.widget.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.MonitorStateListener
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.widget.di.WidgetScope
import javax.inject.Inject
import javax.inject.Singleton

/** The home-screen surfaces that render [MonitorsWidgetState]; Android implementation in `AndroidWidgetSurfaces`. */
interface WidgetSurfaces {
    /** Redraws all placed widgets (no-op without widgets). */
    suspend fun refreshWidgets()

    /** Asks the system to bind the Quick Settings tile so it can redraw (no-op when not added). */
    fun requestTileUpdate()
}

/**
 * Latest state shown by widget and tile. Glance sessions and the tile collect [state]; [current] loads it
 * from the cache (Room, works offline) when nothing was published yet, e.g. in a process started by the
 * widget host.
 */
@Singleton
class WidgetStateHolder
    @Inject
    constructor(
        private val monitors: MonitorRepository,
        private val session: SessionRepository,
    ) {
        private val mutableState = MutableStateFlow<MonitorsWidgetState?>(null)
        val state: StateFlow<MonitorsWidgetState?> = mutableState.asStateFlow()

        /** Cache-backed source of states; emits on every cache change. */
        fun observeSource(): Flow<MonitorsWidgetState> =
            combine(
                session.observeServerConfig().map { it != null }.distinctUntilChanged(),
                monitors.observeMonitors(),
            ) { configured, list -> WidgetStateMapper.map(configured, list) }
                .distinctUntilChanged()

        suspend fun current(): MonitorsWidgetState =
            mutableState.value ?: observeSource().first().also { mutableState.compareAndSet(null, it) }

        internal fun publish(state: MonitorsWidgetState) {
            mutableState.value = state
        }
    }

/**
 * Keeps widget and tile in sync with the monitor cache while the app process runs: no polling of its own,
 * it only reacts to cache changes (live socket in foreground, push-triggered syncs in background), filtered
 * by [WidgetUpdatePolicy]. Started once per process via [start] (see `WidgetInitializer`).
 */
@Singleton
class WidgetUpdater internal constructor(
    private val holder: WidgetStateHolder,
    private val surfaces: WidgetSurfaces,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val policy: WidgetUpdatePolicy,
) {
    @Inject
    constructor(
        holder: WidgetStateHolder,
        surfaces: WidgetSurfaces,
        @WidgetScope scope: CoroutineScope,
    ) : this(holder, surfaces, scope, System::currentTimeMillis, WidgetUpdatePolicy())

    private val lock = Mutex()
    private var job: Job? = null
    private var publishedAtMillis = 0L

    /** Idempotent. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { holder.observeSource().collect { publish(it, force = false) } }
    }

    /** Re-reads the cache and redraws widget and tile unconditionally (e.g. after a push-triggered sync). */
    suspend fun refreshNow() {
        publish(holder.observeSource().first(), force = true)
    }

    private suspend fun publish(
        next: MonitorsWidgetState,
        force: Boolean,
    ) {
        lock.withLock {
            val previous = holder.state.value
            val now = nowMillis()
            if (!force && !policy.shouldPublish(previous, next, publishedAtMillis, now)) return
            holder.publish(next)
            publishedAtMillis = now
            surfaces.refreshWidgets()
            if (force || previous == null || TileSummary.of(previous) != TileSummary.of(next)) {
                surfaces.requestTileUpdate()
            }
        }
    }
}

/** Redraws widget and tile after :push refreshed the cache in the background. */
class WidgetMonitorStateListener
    @Inject
    constructor(
        private val updater: WidgetUpdater,
    ) : MonitorStateListener {
        override suspend fun onMonitorStateChanged() {
            updater.refreshNow()
        }
    }
