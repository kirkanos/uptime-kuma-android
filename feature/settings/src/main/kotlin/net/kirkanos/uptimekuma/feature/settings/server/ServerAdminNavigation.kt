package net.kirkanos.uptimekuma.feature.settings.server

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.about.AboutServerEntry
import net.kirkanos.uptimekuma.feature.settings.server.apikeys.ApiKeysEntry
import net.kirkanos.uptimekuma.feature.settings.server.history.MonitorHistoryEntry
import net.kirkanos.uptimekuma.feature.settings.server.infra.DockerHostsEntry
import net.kirkanos.uptimekuma.feature.settings.server.infra.ProxiesEntry
import net.kirkanos.uptimekuma.feature.settings.server.infra.RemoteBrowsersEntry
import net.kirkanos.uptimekuma.feature.settings.server.proxy.ReverseProxyEntry
import net.kirkanos.uptimekuma.feature.settings.server.security.SecurityEntry
import net.kirkanos.uptimekuma.feature.settings.server.settings.ExpiryNotificationEntry
import net.kirkanos.uptimekuma.feature.settings.server.settings.GeneralSettingsEntry

/** Server administration sub-screen (one per web UI settings page). */
@Serializable
data class ServerAdminRoute(
    val section: ServerSection,
)

/** The Kuma web UI's Settings pages, in its order, as rows of the settings screen's "Server" section. */
@Keep
enum class ServerSection(
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val icon: ImageVector,
) {
    GENERAL(R.string.admin_general_title, R.string.admin_general_summary, Icons.Outlined.Tune),
    NOTIFICATIONS(R.string.admin_expiry_title, R.string.admin_expiry_summary, Icons.Outlined.NotificationsActive),
    REVERSE_PROXY(R.string.admin_reverse_proxy_title, R.string.admin_reverse_proxy_summary, Icons.Outlined.VpnLock),
    SECURITY(R.string.admin_security_title, R.string.admin_security_summary, Icons.Outlined.Lock),
    API_KEYS(R.string.admin_api_keys_title, R.string.admin_api_keys_summary, Icons.Outlined.Key),
    PROXIES(R.string.admin_proxies_title, R.string.admin_proxies_summary, Icons.Outlined.Router),
    DOCKER_HOSTS(R.string.admin_docker_title, R.string.admin_docker_summary, Icons.Outlined.ViewInAr),
    REMOTE_BROWSERS(R.string.admin_browsers_title, R.string.admin_browsers_summary, Icons.Outlined.Language),
    MONITOR_HISTORY(R.string.admin_history_title, R.string.admin_history_summary, Icons.Outlined.Storage),
    ABOUT(R.string.admin_about_title, R.string.admin_about_summary, Icons.Outlined.Info),
}

/** Registers the server administration screens; part of `settingsGraph`. */
internal fun NavGraphBuilder.serverAdminScreens(navController: NavController) {
    composable<ServerAdminRoute> { entry ->
        val route = entry.toRoute<ServerAdminRoute>()
        val onBack: () -> Unit = { navController.popBackStack() }
        when (route.section) {
            ServerSection.GENERAL -> GeneralSettingsEntry(onBack)
            ServerSection.NOTIFICATIONS -> ExpiryNotificationEntry(onBack)
            ServerSection.REVERSE_PROXY -> ReverseProxyEntry(onBack)
            ServerSection.SECURITY -> SecurityEntry(onBack)
            ServerSection.API_KEYS -> ApiKeysEntry(onBack)
            ServerSection.PROXIES -> ProxiesEntry(onBack)
            ServerSection.DOCKER_HOSTS -> DockerHostsEntry(onBack)
            ServerSection.REMOTE_BROWSERS -> RemoteBrowsersEntry(onBack)
            ServerSection.MONITOR_HISTORY -> MonitorHistoryEntry(onBack)
            ServerSection.ABOUT -> AboutServerEntry(onBack)
        }
    }
}
