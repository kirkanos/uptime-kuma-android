package net.kirkanos.uptimekuma.feature.monitors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the monitors feature. */
const val MONITORS_ROUTE: String = "monitors"

/** Entry point of the monitors feature; placeholder until the real screen is implemented. */
@Composable
fun MonitorsRoute(modifier: Modifier = Modifier) {
    MonitorsScreen(modifier = modifier)
}

@Composable
internal fun MonitorsScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Monitors", style = MaterialTheme.typography.headlineMedium)
    }
}
