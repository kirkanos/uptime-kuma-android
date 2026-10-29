package net.kirkanos.uptimekuma.core.data.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import net.kirkanos.uptimekuma.core.data.db.CertEntity
import net.kirkanos.uptimekuma.core.data.db.HeartbeatEntity
import net.kirkanos.uptimekuma.core.data.db.KumaDatabase
import net.kirkanos.uptimekuma.core.data.db.MonitorEntity
import net.kirkanos.uptimekuma.core.data.db.MonitorTagEntity
import net.kirkanos.uptimekuma.core.data.db.UptimeEntity
import net.kirkanos.uptimekuma.core.data.di.DataScope
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cached monitors joined into [MonitorOverview]s, shared by all collectors (dashboard, detail,
 * widget, tile) so the Room queries run once instead of once per screen.
 *
 * Large servers send many events per second (500 monitors at a 20 s interval = 25 heartbeats/s, plus a
 * burst of 500 `heartbeatList`s after login). Every write invalidates the queries, so emissions are
 * throttled to one per [THROTTLE_MILLIS]: the first value is immediate, later ones wait. Each table's
 * flow is throttled by suspending its collector, which back-pressures Room: invalidations that arrive
 * meanwhile are coalesced into a single re-query. The join itself ([OverviewBuilder]) runs on the newest
 * snapshot only, at most once per period, and the latest state always arrives.
 */
@Singleton
internal class MonitorOverviewSource
    @Inject
    constructor(
        db: KumaDatabase,
        @DataScope scope: CoroutineScope,
    ) {
        val overviews: Flow<List<MonitorOverview>> =
            overviewFlow(
                monitors = db.monitorDao().observeAll(),
                tags = db.monitorDao().observeAllTags(),
                beats = db.heartbeatDao().observeLatest(HeartbeatBuffer.OVERVIEW_BEATS),
                uptime = db.statsDao().observeUptime(),
                certs = db.statsDao().observeCerts(),
                maintenance = db.maintenanceDao().observeIds(),
            ).shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

        companion object {
            const val THROTTLE_MILLIS = 250L

            /** Keeps the queries alive across configuration changes and quick screen switches. */
            private const val STOP_TIMEOUT_MILLIS = 5_000L

            @Suppress("LongParameterList")
            fun overviewFlow(
                monitors: Flow<List<MonitorEntity>>,
                tags: Flow<List<MonitorTagEntity>>,
                beats: Flow<List<HeartbeatEntity>>,
                uptime: Flow<List<UptimeEntity>>,
                certs: Flow<List<CertEntity>>,
                maintenance: Flow<List<Long>>,
                throttleMillis: Long = THROTTLE_MILLIS,
            ): Flow<List<MonitorOverview>> {
                // Back pressure per table: a throttled collector suspends Room's query loop, so the
                // invalidations of a burst collapse into one re-query (at most one per period and table).
                fun <T> Flow<T>.paced() = throttle(throttleMillis)
                return combine(
                    combine(monitors.paced(), tags.paced(), ::Pair),
                    beats.paced(),
                    uptime.paced(),
                    certs.paced(),
                    maintenance.paced(),
                ) { (monitorList, tagList), beatList, uptimeList, certList, maintenanceIds ->
                    Inputs(monitorList, tagList, beatList, uptimeList, certList, maintenanceIds)
                }
                    // Cheap snapshots; keep only the newest and build at most once per period.
                    .conflate()
                    .throttle(throttleMillis)
                    .map {
                        OverviewBuilder.build(
                            it.monitors,
                            it.tags,
                            it.beats,
                            it.uptime,
                            it.certs,
                            it.maintenance.toSet(),
                        )
                    }.distinctUntilChanged()
            }

            private class Inputs(
                val monitors: List<MonitorEntity>,
                val tags: List<MonitorTagEntity>,
                val beats: List<HeartbeatEntity>,
                val uptime: List<UptimeEntity>,
                val certs: List<CertEntity>,
                val maintenance: List<Long>,
            )

            /** Emits a value, then suspends for [periodMillis] before accepting the next (back pressure). */
            fun <T> Flow<T>.throttle(periodMillis: Long): Flow<T> =
                transform {
                    emit(it)
                    delay(periodMillis)
                }
        }
    }
