package net.kirkanos.uptimekuma.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Navigation route of the onboarding feature. */
const val ONBOARDING_ROUTE: String = "onboarding"

/** Entry point of the onboarding feature; placeholder until the real screen is implemented. */
@Composable
fun OnboardingRoute(modifier: Modifier = Modifier) {
    OnboardingScreen(modifier = modifier)
}

@Composable
internal fun OnboardingScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Onboarding", style = MaterialTheme.typography.headlineMedium)
    }
}
