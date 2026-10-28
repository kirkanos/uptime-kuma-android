package net.kirkanos.uptimekuma.feature.maintenance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the maintenance feature. */
const val MAINTENANCE_ROUTE: String = "maintenance"

/** Entry point of the maintenance feature; placeholder until the real screen is implemented. */
@Composable
fun MaintenanceRoute(modifier: Modifier = Modifier) {
    MaintenanceScreen(modifier = modifier)
}

@Composable
internal fun MaintenanceScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Maintenance", style = MaterialTheme.typography.headlineMedium)
    }
}
