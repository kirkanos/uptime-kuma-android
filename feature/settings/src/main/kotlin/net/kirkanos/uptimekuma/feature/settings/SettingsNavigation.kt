package net.kirkanos.uptimekuma.feature.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.settings.history.AlertHistoryEntry
import net.kirkanos.uptimekuma.feature.settings.main.SettingsEntry
import net.kirkanos.uptimekuma.feature.settings.server.ServerAdminRoute
import net.kirkanos.uptimekuma.feature.settings.server.serverAdminScreens

/** Public entry route of the settings feature. */
@Serializable
data object SettingsRoute

/** In-app list of received push alerts. */
@Serializable
data object AlertHistoryRoute

/**
 * A row in the settings screen's "Administration" section (maintenance, status pages, tags, notification
 * providers, ...). Other features contribute these through the app's [settingsGraph] call.
 */
data class AdministrationEntry(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
    @StringRes val summaryRes: Int? = null,
)

/**
 * Settings screen ([SettingsRoute]), alert history ([AlertHistoryRoute]) and the server administration screens
 * ([net.kirkanos.uptimekuma.feature.settings.server.ServerAdminRoute]).
 *
 * Logout clears the server config; the app's nav host reacts to that and returns to onboarding, so no
 * callback is needed for it.
 */
fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onMonitorClick: (Long) -> Unit,
    onPushSetup: () -> Unit,
    administrationEntries: List<AdministrationEntry> = emptyList(),
) {
    composable<SettingsRoute> {
        SettingsEntry(
            onBack = { navController.popBackStack() },
            onPushSetup = onPushSetup,
            onAlertHistory = { navController.navigate(AlertHistoryRoute) { launchSingleTop = true } },
            onServerSection = { section ->
                navController.navigate(ServerAdminRoute(section)) { launchSingleTop = true }
            },
            administrationEntries = administrationEntries,
        )
    }
    composable<AlertHistoryRoute> {
        AlertHistoryEntry(onBack = { navController.popBackStack() }, onMonitorClick = onMonitorClick)
    }
    serverAdminScreens(navController)
}
