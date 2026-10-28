package net.kirkanos.uptimekuma.feature.monitors

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.monitors.dashboard.DashboardEntry
import net.kirkanos.uptimekuma.feature.monitors.detail.MonitorDetailEntry

/** Type-safe route of the monitor dashboard (list). */
@Serializable
data object DashboardRoute

/** Type-safe route of a monitor's detail screen. */
@Serializable
data class MonitorDetailRoute(
    val monitorId: Long,
) {
    companion object {
        /** SavedStateHandle key; must equal the property name above. */
        const val ARG_MONITOR_ID = "monitorId"
    }
}

fun NavGraphBuilder.dashboardScreen(
    onMonitorClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onReLogin: () -> Unit,
) {
    composable<DashboardRoute> {
        DashboardEntry(onMonitorClick = onMonitorClick, onSettingsClick = onSettingsClick, onReLogin = onReLogin)
    }
}

fun NavGraphBuilder.monitorDetailScreen(onBack: () -> Unit) {
    composable<MonitorDetailRoute> { MonitorDetailEntry(onBack = onBack) }
}
