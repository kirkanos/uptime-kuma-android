package net.kirkanos.uptimekuma.feature.settings.server.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.ui.component.ErrorState
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.feature.settings.R
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessageSnackbar
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminScaffold

/**
 * Scaffold of a settings form screen: loading / error states, a Save action enabled while the draft differs from
 * the stored settings, and the snackbar for save results. [content] gets the current draft.
 */
@Composable
internal fun SettingsFormScaffold(
    title: String,
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    showSave: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (ServerSettings) -> Unit,
) {
    AdminMessageSnackbar(state.message, snackbarHostState, onMessageShown)
    AdminScaffold(
        title = title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (showSave && state.draft != null) {
                TextButton(onClick = onSave, enabled = state.canSave) {
                    Text(stringResource(if (state.saving) R.string.admin_saving else R.string.admin_save))
                }
            }
        },
    ) { modifier ->
        val draft = state.draft
        when {
            draft != null -> {
                Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(draft) }
            }

            state.loadError != null -> {
                ErrorState(state.loadError, modifier, onRetry)
            }

            else -> {
                LoadingState(modifier)
            }
        }
    }
}
