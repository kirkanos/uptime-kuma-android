package net.kirkanos.uptimekuma.feature.notifications

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.notifications.edit.NotificationEditEntry
import net.kirkanos.uptimekuma.feature.notifications.list.NotificationListEntry
import net.kirkanos.uptimekuma.feature.notifications.picker.ProviderPickerEntry

/** Public entry route: the list of Kuma notification providers. */
@Serializable
data object NotificationListRoute

/** Provider type picker for a new notification. */
@Serializable
data object NotificationProviderPickerRoute

/** Editor. [notificationId] = [NEW] creates a notification of [type]. */
@Serializable
data class NotificationEditRoute(
    val notificationId: Long = NEW,
    val type: String = "",
) {
    companion object {
        const val NEW = -1L

        /** SavedStateHandle keys; must equal the property names above. */
        const val ARG_ID = "notificationId"
        const val ARG_TYPE = "type"
    }
}

/** Notification list, provider picker and editor. */
fun NavGraphBuilder.notificationsGraph(navController: NavController) {
    composable<NotificationListRoute> {
        NotificationListEntry(
            onBack = { navController.popBackStack() },
            onAdd = { navController.navigate(NotificationProviderPickerRoute) },
            onOpen = { id -> navController.navigate(NotificationEditRoute(notificationId = id)) },
        )
    }
    composable<NotificationProviderPickerRoute> {
        ProviderPickerEntry(
            onBack = { navController.popBackStack() },
            onPick = { type ->
                navController.navigate(NotificationEditRoute(type = type)) {
                    popUpTo<NotificationProviderPickerRoute> { inclusive = true }
                }
            },
        )
    }
    composable<NotificationEditRoute> {
        NotificationEditEntry(onDone = { navController.popBackStack() })
    }
}
