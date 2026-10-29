package net.kirkanos.uptimekuma.feature.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.detail.MaintenanceDetailEntry
import net.kirkanos.uptimekuma.feature.maintenance.edit.MaintenanceEditEntry
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceListEntry

/** Public entry route: the maintenance list. */
@Serializable
data object MaintenanceListRoute

/** Detail of one maintenance. */
@Serializable
data class MaintenanceDetailRoute(
    val maintenanceId: Long,
) {
    companion object {
        /** SavedStateHandle key; must equal the property name above. */
        const val ARG_ID = "maintenanceId"
    }
}

/** Editor. [maintenanceId] = [NEW] creates one; [clone] = true pre-fills a new one from [maintenanceId]. */
@Serializable
data class MaintenanceEditRoute(
    val maintenanceId: Long = NEW,
    val clone: Boolean = false,
) {
    companion object {
        const val NEW = -1L
        const val ARG_ID = "maintenanceId"
        const val ARG_CLONE = "clone"
    }
}

/**
 * Maintenance list, detail and editor. [onMonitorClick] opens a monitor (affected monitors in the detail);
 * the app wires it to its monitor detail route.
 */
fun NavGraphBuilder.maintenanceGraph(
    navController: NavController,
    onMonitorClick: (Long) -> Unit = {},
) {
    composable<MaintenanceListRoute> {
        MaintenanceListEntry(
            onBack = { navController.popBackStack() },
            onOpen = { id -> navController.navigate(MaintenanceDetailRoute(id)) },
            onAdd = { navController.navigate(MaintenanceEditRoute()) },
            onEdit = { id -> navController.navigate(MaintenanceEditRoute(id)) },
            onClone = { id -> navController.navigate(MaintenanceEditRoute(id, clone = true)) },
        )
    }
    composable<MaintenanceDetailRoute> {
        MaintenanceDetailEntry(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(MaintenanceEditRoute(id)) },
            onClone = { id -> navController.navigate(MaintenanceEditRoute(id, clone = true)) },
            onMonitorClick = onMonitorClick,
        )
    }
    composable<MaintenanceEditRoute> {
        MaintenanceEditEntry(onDone = { navController.popBackStack() })
    }
}

/** Settings list entry that opens [MaintenanceListRoute]; carries its own (EN/DE) strings. */
@Composable
fun MaintenanceSettingsItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.maintenance_title)) },
        supportingContent = { Text(stringResource(R.string.maintenance_settings_summary)) },
        leadingContent = { Icon(Icons.Outlined.Construction, contentDescription = null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Preview(showBackground = true)
@Composable
private fun MaintenanceSettingsItemPreview() {
    KumaTheme(dynamicColor = false) { MaintenanceSettingsItem(onClick = {}) }
}
