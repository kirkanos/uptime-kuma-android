package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.monitors.DashboardRoute
import net.kirkanos.uptimekuma.feature.monitors.MonitorDetailRoute
import net.kirkanos.uptimekuma.feature.monitors.tags.TagManagerEntry

/** Picks the type of a new monitor, then opens the editor. */
@Serializable
data object MonitorTypePickerRoute

/**
 * The monitor editor. Exactly one mode: [monitorId] = edit, [cloneOf] = clone, otherwise a new monitor of [type].
 * Absent ids are [NONE] (navigation arguments stay non-null).
 */
@Serializable
data class MonitorEditorRoute(
    val monitorId: Long = NONE,
    val cloneOf: Long = NONE,
    val type: String = "http",
) {
    companion object {
        const val NONE = -1L

        /** SavedStateHandle keys; must equal the property names above. */
        const val ARG_MONITOR_ID = "monitorId"
        const val ARG_CLONE_OF = "cloneOf"
        const val ARG_TYPE = "type"
    }
}

/** Tag definitions: list, create, rename/recolor, delete. */
@Serializable
data object TagManagerRoute

/** Type picker, editor and tag manager. Entry points: [MonitorTypePickerRoute], [MonitorEditorRoute], [TagManagerRoute]. */
fun NavGraphBuilder.monitorEditorGraph(navController: NavController) {
    composable<MonitorTypePickerRoute> {
        TypePickerEntry(
            onBack = { navController.popBackStack() },
            onTypeSelected = { type ->
                navController.navigate(MonitorEditorRoute(type = type)) {
                    popUpTo<MonitorTypePickerRoute> { inclusive = true }
                }
            },
        )
    }
    composable<MonitorEditorRoute> { entry ->
        val route = entry.toRoute<MonitorEditorRoute>()
        MonitorEditorEntry(
            onClose = { navController.popBackStack() },
            onSaved = { id ->
                if (route.monitorId != MonitorEditorRoute.NONE) {
                    // Edited from the detail screen: go back to it.
                    navController.popBackStack()
                } else {
                    navController.navigate(MonitorDetailRoute(id)) {
                        popUpTo<MonitorEditorRoute> { inclusive = true }
                    }
                }
            },
            onDeleted = {
                if (!navController.popBackStack<DashboardRoute>(inclusive = false)) navController.popBackStack()
            },
            onManageTags = { navController.navigate(TagManagerRoute) },
        )
    }
    composable<TagManagerRoute> {
        TagManagerEntry(onBack = { navController.popBackStack() })
    }
}
