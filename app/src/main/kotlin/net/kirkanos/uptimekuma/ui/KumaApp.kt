package net.kirkanos.uptimekuma.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.monitors.DashboardRoute
import net.kirkanos.uptimekuma.feature.monitors.MonitorDetailRoute
import net.kirkanos.uptimekuma.feature.monitors.dashboardScreen
import net.kirkanos.uptimekuma.feature.monitors.editor.MonitorEditorRoute
import net.kirkanos.uptimekuma.feature.monitors.editor.MonitorTypePickerRoute
import net.kirkanos.uptimekuma.feature.monitors.editor.TagManagerRoute
import net.kirkanos.uptimekuma.feature.monitors.editor.monitorEditorGraph
import net.kirkanos.uptimekuma.feature.monitors.monitorDetailScreen
import net.kirkanos.uptimekuma.feature.onboarding.OnboardingRoute
import net.kirkanos.uptimekuma.feature.onboarding.onboardingScreen
import net.kirkanos.uptimekuma.push.ui.PushSetupRoute
import net.kirkanos.uptimekuma.ui.settings.SettingsScreen

/** Push notification setup. [fromOnboarding] = continue to the dashboard when done instead of going back. */
@Serializable
data class PushSetupDestination(
    val fromOnboarding: Boolean = false,
)

@Serializable
data object SettingsDestination

/**
 * Root composable: navigation between onboarding, dashboard, monitor detail, settings and push setup.
 * The start destination follows the stored server config (none -> onboarding); losing the config later
 * (logout) returns to onboarding with a cleared back stack.
 */
@Composable
fun KumaApp(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val gate by appViewModel.gate.collectAsStateWithLifecycle()
    val ready = gate as? SessionGate.Ready ?: return // splash screen is still visible
    val navController = rememberNavController()
    val startDestination: Any = remember { if (ready.configured) DashboardRoute else OnboardingRoute }

    LaunchedEffect(ready.configured) {
        val current = navController.currentDestination
        if (!ready.configured && current != null && !current.hasRoute<OnboardingRoute>()) {
            navController.navigate(OnboardingRoute) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    val pendingMonitorId by appViewModel.pendingMonitorId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMonitorId, ready.configured) {
        val id = pendingMonitorId ?: return@LaunchedEffect
        if (ready.configured) {
            navController.navigate(MonitorDetailRoute(id)) { launchSingleTop = true }
        }
        appViewModel.consumePendingMonitor()
    }

    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        onboardingScreen(onFinished = { reLogin -> navController.onLoginFinished(reLogin) })

        composable<PushSetupDestination> { entry ->
            val route = entry.toRoute<PushSetupDestination>()
            PushSetupRoute(
                onDone = {
                    if (route.fromOnboarding) navController.goToDashboard() else navController.popBackStack()
                },
            )
        }

        dashboardScreen(
            onMonitorClick = { id -> navController.navigate(MonitorDetailRoute(id)) },
            onSettingsClick = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
            onReLogin = { navController.navigate(OnboardingRoute) { launchSingleTop = true } },
            onAddMonitor = { navController.navigate(MonitorTypePickerRoute) },
            onManageTags = { navController.navigate(TagManagerRoute) },
        )

        monitorDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(MonitorEditorRoute(monitorId = id)) },
            onClone = { id -> navController.navigate(MonitorEditorRoute(cloneOf = id)) },
        )
        monitorEditorGraph(navController)

        composable<SettingsDestination> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPushSetup = { navController.navigate(PushSetupDestination(fromOnboarding = false)) },
            )
        }
    }
}

private fun NavHostController.goToDashboard() {
    navigate(DashboardRoute) { popUpTo(graph.id) { inclusive = true } }
}

private fun NavHostController.onLoginFinished(reLogin: Boolean) {
    when {
        reLogin && previousBackStackEntry != null -> popBackStack()
        reLogin -> goToDashboard()
        else -> navigate(PushSetupDestination(fromOnboarding = true)) { popUpTo(graph.id) { inclusive = true } }
    }
}
