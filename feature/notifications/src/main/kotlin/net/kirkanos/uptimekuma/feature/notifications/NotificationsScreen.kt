package net.kirkanos.uptimekuma.feature.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the notifications feature. */
const val NOTIFICATIONS_ROUTE: String = "notifications"

/** Entry point of the notifications feature; placeholder until the real screen is implemented. */
@Composable
fun NotificationsRoute(modifier: Modifier = Modifier) {
    NotificationsScreen(modifier = modifier)
}

@Composable
internal fun NotificationsScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Notifications", style = MaterialTheme.typography.headlineMedium)
    }
}
