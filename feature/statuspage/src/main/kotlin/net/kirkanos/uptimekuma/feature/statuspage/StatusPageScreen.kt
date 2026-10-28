package net.kirkanos.uptimekuma.feature.statuspage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the statuspage feature. */
const val STATUS_PAGE_ROUTE: String = "statuspage"

/** Entry point of the statuspage feature; placeholder until the real screen is implemented. */
@Composable
fun StatusPageRoute(modifier: Modifier = Modifier) {
    StatusPageScreen(modifier = modifier)
}

@Composable
internal fun StatusPageScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "StatusPage", style = MaterialTheme.typography.headlineMedium)
    }
}
