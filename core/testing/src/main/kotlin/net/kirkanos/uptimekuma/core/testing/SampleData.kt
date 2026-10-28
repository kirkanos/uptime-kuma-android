package net.kirkanos.uptimekuma.core.testing

import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.ServerInfo
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.model.UptimeStats
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Realistic, deterministic sample data modelled on the Kuma 2.5.8 fixtures: plain monitors of several
 * types, a group with two children, tags, a down monitor, a paused one, one in maintenance, a pending one
 * and a certificate that expires soon. All times are relative to the `nowMillis` argument.
 */
@Suppress("MagicNumber")
object SampleData {
    const val BEATS_PER_MONITOR = 100

    val serverConfig = ServerConfig(baseUrl = "https://uptime.example.org", username = "admin")
    val serverInfo = ServerInfo(version = "2.5.8", latestVersion = "2.5.8", primaryBaseUrl = serverConfig.baseUrl)

    val tagEnv = Tag(id = 1, name = "env", color = "#059669")
    val tagTeam = Tag(id = 2, name = "team", color = "#7C3AED")
    val tagCritical = Tag(id = 3, name = "critical", color = "#DC2626")
    val tags: List<Tag> = listOf(tagEnv, tagTeam, tagCritical)

    private fun Tag.on(value: String = "") = MonitorTag(tagId = id, name = name, color = color, value = value)

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    /** Monitor definitions in Kuma's order, plus the status pattern of their recent beats. */
    private data class Spec(
        val monitor: Monitor,
        /** Status of the last beats, newest last; earlier beats are UP. */
        val tail: List<MonitorStatus> = emptyList(),
        val basePing: Double? = 80.0,
        val uptime24h: Double = 1.0,
        val uptime30d: Double = 0.9995,
        val uptime1y: Double = 0.9989,
        val certDays: Int? = null,
        val inMaintenance: Boolean = false,
    )

    private val specs: List<Spec> =
        listOf(
            Spec(
                Monitor(
                    id = 1,
                    name = "Website",
                    type = "http",
                    url = "https://example.com",
                    intervalSeconds = 60,
                    tags = listOf(tagEnv.on("prod"), tagCritical.on()),
                    expiryNotification = true,
                    weight = 2000,
                ),
                basePing = 95.0,
                certDays = 64,
            ),
            Spec(
                Monitor(
                    id = 2,
                    name = "Public API",
                    type = "keyword",
                    url = "https://api.example.com/health",
                    intervalSeconds = 30,
                    tags = listOf(tagEnv.on("prod"), tagTeam.on("backend")),
                    expiryNotification = true,
                    weight = 1990,
                ),
                tail = listOf(MonitorStatus.PENDING, MonitorStatus.DOWN, MonitorStatus.UP),
                basePing = 140.0,
                uptime24h = 0.9965,
                uptime30d = 0.9981,
                certDays = 9,
            ),
            Spec(
                Monitor(
                    id = 9,
                    name = "Legacy shop",
                    type = "http",
                    url = "http://shop.example.com",
                    description = "Old shop frontend, scheduled for retirement",
                    intervalSeconds = 60,
                    tags = listOf(tagEnv.on("prod")),
                    weight = 1980,
                ),
                tail = List(8) { MonitorStatus.DOWN },
                basePing = 310.0,
                uptime24h = 0.9444,
                uptime30d = 0.9871,
                uptime1y = 0.9902,
            ),
            Spec(
                Monitor(
                    id = 11,
                    name = "Web cluster",
                    type = Monitor.TYPE_GROUP,
                    childrenIds = listOf(12, 13),
                    intervalSeconds = 60,
                    weight = 1970,
                ),
                basePing = null,
            ),
            Spec(
                Monitor(
                    id = 12,
                    name = "Node A",
                    type = "http",
                    url = "https://node-a.example.com",
                    parentId = 11,
                    pathName = "Web cluster / Node A",
                    intervalSeconds = 60,
                    tags = listOf(tagEnv.on("prod")),
                    weight = 1000,
                ),
                basePing = 45.0,
                certDays = 82,
            ),
            Spec(
                Monitor(
                    id = 13,
                    name = "Node B",
                    type = "port",
                    hostname = "node-b.example.com",
                    port = 443,
                    parentId = 11,
                    pathName = "Web cluster / Node B",
                    intervalSeconds = 60,
                    weight = 1000,
                ),
                tail = listOf(MonitorStatus.DOWN, MonitorStatus.DOWN, MonitorStatus.UP, MonitorStatus.UP),
                basePing = 12.0,
                uptime24h = 0.9986,
            ),
            Spec(
                Monitor(
                    id = 3,
                    name = "Login service",
                    type = "json-query",
                    url = "https://auth.example.com/api/status",
                    intervalSeconds = 20,
                    tags = listOf(tagTeam.on("identity")),
                    weight = 1960,
                ),
                tail = listOf(MonitorStatus.UP, MonitorStatus.PENDING, MonitorStatus.PENDING),
                basePing = 220.0,
                uptime24h = 0.9992,
                certDays = 120,
            ),
            Spec(
                Monitor(
                    id = 4,
                    name = "Mail server",
                    type = "port",
                    hostname = "mail.example.com",
                    port = 587,
                    intervalSeconds = 120,
                    weight = 1950,
                ),
                basePing = 18.0,
            ),
            Spec(
                Monitor(id = 5, name = "Cloudflare DNS", type = "ping", hostname = "1.1.1.1", weight = 1940),
                basePing = 6.0,
            ),
            Spec(
                Monitor(
                    id = 6,
                    name = "DNS example.com A",
                    type = "dns",
                    hostname = "example.com",
                    port = 53,
                    weight = 1930,
                ),
                basePing = 22.0,
            ),
            Spec(
                Monitor(id = 7, name = "Nightly backup", type = Monitor.TYPE_PUSH, intervalSeconds = 86_400),
                basePing = null,
            ),
            Spec(
                Monitor(
                    id = 10,
                    name = "Docs portal",
                    type = "http",
                    url = "https://docs.example.net",
                    tags = listOf(tagEnv.on("prod")),
                    weight = 1920,
                ),
                tail = List(6) { MonitorStatus.MAINTENANCE },
                basePing = 160.0,
                certDays = 41,
                inMaintenance = true,
            ),
            Spec(
                Monitor(
                    id = 14,
                    name = "Staging website",
                    type = "http",
                    url = "https://staging.example.com",
                    active = false,
                    tags = listOf(tagEnv.on("staging")),
                    weight = 1910,
                ),
                basePing = 120.0,
                uptime24h = 0.0,
                uptime30d = 0.8123,
            ),
        )

    /** All sample monitors with status, beats, uptime and certificate info. */
    fun monitors(nowMillis: Long = System.currentTimeMillis()): List<MonitorOverview> =
        specs.map { spec ->
            val beats = beats(spec, nowMillis)
            MonitorOverview(
                monitor = spec.monitor,
                status = beats.lastOrNull()?.status,
                recentBeats = beats,
                uptime =
                    UptimeStats(
                        monitorId = spec.monitor.id,
                        uptime24h = spec.uptime24h,
                        uptime30d = spec.uptime30d,
                        uptime1y = spec.uptime1y,
                        avgPingMs = spec.basePing,
                    ),
                cert = spec.certDays?.let { cert(spec.monitor.id, it, nowMillis) },
                inMaintenance = spec.inMaintenance,
            )
        }

    private fun beats(
        spec: Spec,
        nowMillis: Long,
    ): List<Heartbeat> {
        val id = spec.monitor.id
        val random = Random(id)
        val interval = spec.monitor.intervalSeconds.coerceAtMost(3_600) * 1_000L
        // A paused monitor stopped beating a while ago.
        val end = if (spec.monitor.active) nowMillis - random.nextLong(5_000, 25_000) else nowMillis - 2 * DAY
        var previous: MonitorStatus? = null
        return List(BEATS_PER_MONITOR) { index ->
            val fromEnd = BEATS_PER_MONITOR - 1 - index
            val status = spec.tail.getOrNull(spec.tail.size - 1 - fromEnd) ?: MonitorStatus.UP
            val ping =
                spec.basePing?.takeIf { status != MonitorStatus.DOWN }?.let {
                    max(1.0, it + it * 0.25 * sin(index / 6.0) + random.nextDouble(-0.1, 0.1) * it)
                }
            val beat =
                Heartbeat(
                    monitorId = id,
                    status = status,
                    timeMillis = end - fromEnd * interval,
                    message = messageFor(spec.monitor, status),
                    pingMs = ping,
                    important = previous == null || (previous != status && status != MonitorStatus.PENDING),
                    durationSeconds = spec.monitor.intervalSeconds,
                )
            previous = status
            beat
        }
    }

    private fun messageFor(
        monitor: Monitor,
        status: MonitorStatus,
    ): String =
        when (status) {
            MonitorStatus.UP -> if (monitor.url != null) "200 - OK" else ""
            MonitorStatus.DOWN -> if (monitor.url != null) "connect ECONNREFUSED" else "timeout of 48000ms exceeded"
            MonitorStatus.PENDING -> "Request failed with status code 503, retrying (1/3)"
            MonitorStatus.MAINTENANCE -> "Monitor under maintenance"
        }

    private fun cert(
        monitorId: Long,
        daysRemaining: Int,
        nowMillis: Long,
    ) = CertInfo(
        monitorId = monitorId,
        valid = true,
        subject = "CN=example.com",
        issuer = if (daysRemaining < 30) "R11 (Let's Encrypt)" else "Sectigo RSA Domain Validation Secure Server CA",
        validFromMillis = nowMillis - (90 - daysRemaining) * DAY,
        validToMillis = nowMillis + daysRemaining * DAY,
        daysRemaining = daysRemaining,
        fingerprint256 = "8F:43:28:8A:D2:72:F3:10:3B:6F:B1:42:84:85:EA:30:14:C0:BC:FE",
    )

    /** Response-time chart for [periodHours], one point per minute up to 6 h, coarser above. */
    fun chart(
        monitorId: Long,
        periodHours: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ChartPoint> {
        val spec = specs.firstOrNull { it.monitor.id == monitorId }
        val base = spec?.basePing ?: return emptyList()
        val step =
            when {
                periodHours <= 6 -> MINUTE
                periodHours <= 24 -> 5 * MINUTE
                else -> 60 * MINUTE
            }
        val count = (periodHours * 60 * MINUTE / step).toInt()
        val random = Random(monitorId * 31 + periodHours)
        val outageStart = count * 2 / 3
        return List(count) { i ->
            val down = monitorId == 2L || monitorId == 9L || monitorId == 13L
            val inOutage = down && i in outageStart..outageStart + count / 40
            ChartPoint(
                timeMillis = nowMillis - (count - 1 - i) * step,
                pingMs =
                    if (inOutage) {
                        null
                    } else {
                        max(1.0, base + base * 0.3 * sin(i / 9.0) + random.nextDouble(-0.15, 0.15) * base)
                    },
                upCount = if (inOutage) 0 else 1,
                downCount = if (inOutage) 1 else 0,
            )
        }
    }

    /** Important events (state changes) of all monitors, newest first: enough for several pages. */
    fun importantEvents(nowMillis: Long = System.currentTimeMillis()): List<Heartbeat> {
        val events = ArrayList<Heartbeat>()
        specs.forEach { spec ->
            val noBeats = spec.basePing == null && spec.monitor.type != Monitor.TYPE_PUSH
            if (spec.monitor.isGroup || noBeats) return@forEach
            val random = Random(spec.monitor.id + 100)
            var time = nowMillis - random.nextLong(10, 120) * MINUTE
            var status = MonitorStatus.UP
            repeat(12) {
                events +=
                    Heartbeat(
                        monitorId = spec.monitor.id,
                        status = status,
                        timeMillis = time,
                        message = messageFor(spec.monitor, status),
                        important = true,
                    )
                status = if (status == MonitorStatus.UP) MonitorStatus.DOWN else MonitorStatus.UP
                time -= random.nextLong(30, 3 * 24 * 60) * MINUTE
            }
        }
        return events.sortedByDescending { it.timeMillis }
    }
}
