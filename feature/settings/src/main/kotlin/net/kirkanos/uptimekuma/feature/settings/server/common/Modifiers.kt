package net.kirkanos.uptimekuma.feature.settings.server.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Vertical scrolling for dialog bodies (forms get long with large fonts). */
@Composable
internal fun Modifier.verticalScrollCompat(): Modifier = verticalScroll(rememberScrollState())
