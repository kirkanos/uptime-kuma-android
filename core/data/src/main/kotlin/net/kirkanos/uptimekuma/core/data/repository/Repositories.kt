package net.kirkanos.uptimekuma.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.core.data.cache.KumaCache
import net.kirkanos.uptimekuma.core.data.cache.OverviewBuilder
import net.kirkanos.uptimekuma.core.data.cache.toModel
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.db.KumaDatabase
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.ServerInfo
import net.kirkanos.uptimekuma.core.model.Tag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultSessionRepository
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
    ) : SessionRepository {
        override fun observeServerConfig(): Flow<ServerConfig?> = manager.serverConfig

        override fun observeConnectionState(): Flow<ConnectionState> = manager.state

        override suspend fun login(
            baseUrl: String,
            username: String,
            password: String,
            totp: String?,
        ): LoginResult = manager.login(baseUrl, username, password, totp)

        override suspend fun logout() = manager.logout()

        override fun setForeground(foreground: Boolean) = manager.setForeground(foreground)

        override suspend fun syncOnce(timeoutMillis: Long): Boolean = manager.syncOnce(timeoutMillis)
    }

@Singleton
internal class ConnectionServerInfoRepository
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
    ) : ServerInfoRepository {
        override fun observeServerInfo(): Flow<ServerInfo?> = manager.serverInfo
    }

@Singleton
internal class RoomMonitorRepository
    @Inject
    constructor(
        private val db: KumaDatabase,
        private val cache: KumaCache,
        private val manager: KumaConnectionManager,
    ) : MonitorRepository {
        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        private val overviews: Flow<List<MonitorOverview>> by lazy {
            val monitorsWithTags = combine(db.monitorDao().observeAll(), db.monitorDao().observeAllTags(), ::Pair)
            combine(
                monitorsWithTags,
                db.heartbeatDao().observeAll(),
                db.statsDao().observeUptime(),
                db.statsDao().observeCerts(),
                db.maintenanceDao().observeIds(),
            ) { (monitors, tags), beats, uptime, certs, maintenance ->
                OverviewBuilder.build(monitors, tags, beats, uptime, certs, maintenance.toSet())
            }.conflate().distinctUntilChanged()
        }

        override fun observeMonitors(): Flow<List<MonitorOverview>> = overviews

        override fun observeMonitor(id: Long): Flow<MonitorOverview?> =
            overviews.map { list -> list.firstOrNull { it.monitor.id == id } }.distinctUntilChanged()

        override suspend fun loadBeats(
            id: Long,
            hours: Int,
        ): List<Heartbeat> = manager.withApi { it.getMonitorBeats(id, hours) }

        override suspend fun loadChart(
            id: Long,
            periodHours: Int,
        ): List<ChartPoint> = manager.withApi { it.getMonitorChartData(id, periodHours) }

        override suspend fun loadImportantEvents(
            monitorId: Long?,
            offset: Int,
            count: Int,
        ): List<Heartbeat> = manager.withApi { it.importantHeartbeats(monitorId, offset, count) }

        override suspend fun pause(id: Long) {
            manager.withApi { it.pauseMonitor(id) }
            cache.setMonitorActive(id, false)
        }

        override suspend fun resume(id: Long) {
            manager.withApi { it.resumeMonitor(id) }
            cache.setMonitorActive(id, true)
        }

        override suspend fun delete(id: Long) {
            manager.withApi { it.deleteMonitor(id) }
            cache.deleteMonitor(id)
        }

        override fun observeTags(): Flow<List<Tag>> = db.tagDao().observeAll().map { list -> list.map { it.toModel() } }
    }

@Singleton
internal class RoomKumaNotificationRepository
    @Inject
    constructor(
        private val db: KumaDatabase,
        private val cache: KumaCache,
        private val manager: KumaConnectionManager,
    ) : KumaNotificationRepository {
        override fun observeNotifications(): Flow<List<KumaNotification>> =
            db.notificationDao().observeAll().map { list -> list.map { it.toModel() } }

        override suspend fun save(
            notification: KumaNotification,
            applyExisting: Boolean,
        ): Long =
            manager.withApi {
                it.addNotification(NotificationConfig.toPayload(notification, applyExisting), notification.id)
            }

        override suspend fun delete(id: Long) {
            manager.withApi { it.deleteNotification(id) }
            cache.deleteNotification(id)
        }

        override suspend fun test(notification: KumaNotification) {
            manager.withApi { it.testNotification(NotificationConfig.toPayload(notification)) }
        }
    }
