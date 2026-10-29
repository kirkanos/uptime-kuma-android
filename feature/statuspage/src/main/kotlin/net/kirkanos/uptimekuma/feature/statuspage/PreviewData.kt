@file:Suppress("MagicNumber")

package net.kirkanos.uptimekuma.feature.statuspage

import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import net.kirkanos.uptimekuma.core.model.StatusPageMaintenance
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.feature.statuspage.view.HistoryState
import net.kirkanos.uptimekuma.feature.statuspage.view.StatusPageViewState

/** Static data for Compose previews. */
internal object PreviewData {
    private const val NOW = 1_761_700_000_000L
    private const val MINUTE = 60_000L
    private const val BEATS = 40

    val config =
        StatusPageConfig(
            id = 1,
            slug = "status",
            title = "Example status",
            description = "Public status of the example services.",
            icon = "/icon.svg",
            footerText = "Operated by Example",
            showTags = true,
            showCertificateExpiry = true,
            domainNameList = listOf("status.example.org"),
        )

    val pages =
        listOf(
            config,
            StatusPageConfig(id = 2, slug = "internal", title = "Internal tools", published = false),
        )

    val groups =
        listOf(
            StatusPageGroup(
                id = 1,
                name = "Services",
                monitors =
                    listOf(
                        StatusPageMonitor(
                            1,
                            "Website",
                            "http",
                            sendUrl = true,
                            url = "https://example.org",
                            tags = listOf(MonitorTag(1, "prod", "#059669")),
                            certExpiryDaysRemaining = 42,
                            validCert = true,
                        ),
                        StatusPageMonitor(2, "API", "http"),
                    ),
            ),
            StatusPageGroup(
                id = 2,
                name = "Infrastructure",
                monitors = listOf(StatusPageMonitor(3, "Database", "port")),
            ),
        )

    val incident =
        Incident(
            id = 1,
            title = "Degraded API performance",
            content = "We are investigating slow responses of the API.",
            style = IncidentStyle.WARNING,
            createdMillis = NOW - 60 * MINUTE,
        )

    val monitors =
        listOf(
            Monitor(1, "Website", "http", url = "https://example.org"),
            Monitor(2, "API", "http", url = "https://api.example.org"),
            Monitor(3, "Database", "port", hostname = "db", port = 5432),
            Monitor(4, "Mail", "smtp"),
            Monitor(5, "DNS", "dns"),
        )

    private fun beats(
        id: Long,
        last: MonitorStatus,
    ) = List(BEATS) { i ->
        Heartbeat(id, if (i >= BEATS - 3) last else MonitorStatus.UP, NOW - (BEATS - i) * MINUTE, pingMs = 80.0)
    }

    fun page(): PublicStatusPage =
        PublicStatusPage(
            config = config,
            incidents = listOf(incident),
            groups = groups,
            maintenances =
                listOf(
                    StatusPageMaintenance(1, "Database upgrade", "Planned upgrade to the next major version."),
                ),
        )

    val heartbeats =
        StatusPageHeartbeats(
            beats =
                mapOf(
                    1L to beats(1, MonitorStatus.UP),
                    2L to beats(2, MonitorStatus.DOWN),
                    3L to beats(3, MonitorStatus.UP),
                ),
            uptime24h = mapOf(1L to 1.0, 2L to 0.9712, 3L to 0.9995),
        )

    fun viewState(): StatusPageViewState =
        StatusPageViewState(
            loading = false,
            page = page(),
            heartbeats = heartbeats,
            history =
                HistoryState(
                    items =
                        listOf(
                            incident,
                            incident.copy(id = 2, title = "Planned downtime", pin = false, active = false),
                        ),
                    loaded = true,
                    hasMore = true,
                ),
        )
}
