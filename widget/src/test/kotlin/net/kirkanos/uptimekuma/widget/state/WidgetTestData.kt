package net.kirkanos.uptimekuma.widget.state

import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Monitor
import net.kirkanos.uptimekuma.core.model.MonitorOverview
import net.kirkanos.uptimekuma.core.model.MonitorStatus

internal const val NOW = 1_761_700_000_000L

@Suppress("LongParameterList")
internal fun overview(
    id: Long,
    status: MonitorStatus?,
    name: String = "Monitor $id",
    active: Boolean = true,
    inMaintenance: Boolean = false,
    type: String = "http",
    beatTime: Long = NOW - id * 1_000,
    ping: Double? = 42.0,
): MonitorOverview =
    MonitorOverview(
        monitor = Monitor(id = id, name = name, type = type, active = active),
        status = status,
        recentBeats =
            if (status == null) {
                emptyList()
            } else {
                listOf(Heartbeat(monitorId = id, status = status, timeMillis = beatTime, pingMs = ping))
            },
        inMaintenance = inMaintenance,
    )
