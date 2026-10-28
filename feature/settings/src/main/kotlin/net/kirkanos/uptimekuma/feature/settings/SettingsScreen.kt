package net.kirkanos.uptimekuma.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the settings feature. */
const val SETTINGS_ROUTE: String = "settings"

/** Entry point of the settings feature; placeholder until the real screen is implemented. */
@Composable
fun SettingsRoute(modifier: Modifier = Modifier) {
    SettingsScreen(modifier = modifier)
}

@Composable
internal fun SettingsScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
    }
}
