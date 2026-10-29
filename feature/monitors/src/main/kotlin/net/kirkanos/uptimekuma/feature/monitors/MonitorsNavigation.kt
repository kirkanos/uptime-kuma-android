package net.kirkanos.uptimekuma.feature.monitors

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
    onAddMonitor: () -> Unit = {},
    onManageTags: () -> Unit = {},
) {
    composable<DashboardRoute> {
        DashboardEntry(
            onMonitorClick = onMonitorClick,
            onSettingsClick = onSettingsClick,
            onReLogin = onReLogin,
            onAddMonitor = onAddMonitor,
            onManageTags = onManageTags,
        )
    }
}

fun NavGraphBuilder.monitorDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit = {},
    onClone: (Long) -> Unit = {},
    alertSettings: @Composable (monitorId: Long) -> Unit = {},
) {
    composable<MonitorDetailRoute> {
        CompositionLocalProvider(LocalMonitorAlertSettings provides alertSettings) {
            MonitorDetailEntry(onBack = onBack, onEdit = onEdit, onClone = onClone)
        }
    }
}

/**
 * Per-monitor alert settings shown below the detail header (provided by the app from :push, so this
 * module does not depend on it). Empty by default, e.g. in previews and tests.
 */
val LocalMonitorAlertSettings = staticCompositionLocalOf<@Composable (Long) -> Unit> { {} }
