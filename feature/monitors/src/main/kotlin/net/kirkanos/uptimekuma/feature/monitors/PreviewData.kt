package net.kirkanos.uptimekuma.feature.monitors

import net.kirkanos.uptimekuma.core.model.CertInfo
import net.kirkanos.uptimekuma.core.model.ChartPoint
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.UptimeStats
import kotlin.math.sin

/** Small fixed data set for @Preview functions (core:testing is test-only). */
@Suppress("MagicNumber")
internal object PreviewData {
    const val NOW = 1_761_700_000_000L

    private fun beats(
        id: Long,
        tail: List<MonitorStatus>,
    ): List<Heartbeat> =
        List(40) { i ->
            val status = tail.getOrNull(tail.size - 40 + i) ?: MonitorStatus.UP
            Heartbeat(
                monitorId = id,
                status = status,
                timeMillis = NOW - (39 - i) * 60_000L - 20_000L,
                message = if (status == MonitorStatus.DOWN) "connect ECONNREFUSED" else "200 - OK",
                pingMs = if (status == MonitorStatus.DOWN) null else 90.0 + 20 * sin(i / 4.0),
                important = i == 0,
            )
        }

    private val prod = MonitorTag(1, "env", "#059669", "prod")

    val website =
        MonitorOverview(
            monitor =
                Monitor(
                    id = 1,
                    name = "Website",
                    type = "http",
                    url = "https://example.com",
                    tags = listOf(prod, MonitorTag(3, "critical", "#DC2626")),
                    description = "Public marketing site",
                ),
            status = MonitorStatus.UP,
            recentBeats = beats(1, emptyList()),
            uptime = UptimeStats(1, 0.9998, 0.9993, 0.9989, 92.0),
            cert =
                CertInfo(
                    monitorId = 1,
                    valid = true,
                    issuer = "R11 (Let's Encrypt)",
                    validToMillis = NOW + 9 * 86_400_000L,
                    daysRemaining = 9,
                ),
        )

    val shop =
        MonitorOverview(
            monitor = Monitor(id = 9, name = "Legacy shop", type = "http", url = "http://shop.example.com"),
            status = MonitorStatus.DOWN,
            recentBeats = beats(9, List(6) { MonitorStatus.DOWN }),
            uptime = UptimeStats(9, 0.944, 0.987, 0.990, 310.0),
        )

    val group =
        MonitorOverview(
            monitor = Monitor(id = 11, name = "Web cluster", type = Monitor.TYPE_GROUP, childrenIds = listOf(12, 13)),
            status = MonitorStatus.UP,
            recentBeats = beats(11, emptyList()),
            uptime = UptimeStats(11, 1.0),
        )

    val nodeA =
        MonitorOverview(
            monitor =
                Monitor(
                    id = 12,
                    name = "Node A",
                    type = "port",
                    hostname = "node-a.example.com",
                    port = 443,
                    parentId = 11,
                ),
            status = MonitorStatus.PENDING,
            recentBeats = beats(12, listOf(MonitorStatus.PENDING, MonitorStatus.PENDING)),
            uptime = UptimeStats(12, 0.9971),
        )

    val docs =
        MonitorOverview(
            monitor = Monitor(id = 10, name = "Docs portal", type = "http", url = "https://docs.example.net"),
            status = MonitorStatus.MAINTENANCE,
            recentBeats = beats(10, List(5) { MonitorStatus.MAINTENANCE }),
            uptime = UptimeStats(10, 1.0),
            inMaintenance = true,
        )

    val staging =
        MonitorOverview(
            monitor =
                Monitor(
                    id = 14,
                    name = "Staging website",
                    type = "http",
                    url = "https://staging.example.com",
                    active = false,
                ),
            status = MonitorStatus.UP,
            recentBeats = beats(14, emptyList()),
            uptime = UptimeStats(14, 0.0),
        )

    val monitors = listOf(website, shop, group, nodeA, docs, staging)

    val chart: List<ChartPoint> =
        List(120) { i ->
            val down = i in 80..83
            ChartPoint(
                timeMillis = NOW - (119 - i) * 12 * 60_000L,
                pingMs = if (down) null else 90 + 25 * sin(i / 7.0),
                upCount = if (down) 0 else 1,
                downCount = if (down) 1 else 0,
            )
        }

    val events: List<Heartbeat> =
        List(6) { i ->
            val status = if (i % 2 == 0) MonitorStatus.UP else MonitorStatus.DOWN
            Heartbeat(
                monitorId = 1,
                status = status,
                timeMillis = NOW - i * 7 * 3_600_000L,
                message = if (status == MonitorStatus.DOWN) "timeout of 48000ms exceeded" else "200 - OK",
                important = true,
            )
        }
}
