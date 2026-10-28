package net.kirkanos.uptimekuma.push.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.core.domain.PushSetupState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.push.R
import javax.inject.Inject

@HiltViewModel
class PushStatusViewModel
    @Inject
    constructor(
        repository: PushRepository,
    ) : ViewModel() {
        val state: StateFlow<PushSetupState?> =
            repository
                .observeState()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** Push status for the settings screen; [onManageClick] should navigate to [PushSetupRoute]. */
@Composable
fun PushStatusCard(
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PushStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PushStatusCardContent(state = state, onManageClick = onManageClick, modifier = modifier)
}

@Composable
fun PushStatusCardContent(
    state: PushSetupState?,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state == PushSetupState.Active
    val icon =
        when (state) {
            PushSetupState.Active -> Icons.Outlined.NotificationsActive
            is PushSetupState.Error -> Icons.Outlined.ErrorOutline
            else -> Icons.Outlined.NotificationsOff
        }
    val statusText =
        when (state) {
            null -> ""
            PushSetupState.Active -> stringResource(R.string.push_status_active)
            PushSetupState.NoDistributor -> stringResource(R.string.push_status_no_distributor)
            PushSetupState.NotConfigured -> stringResource(R.string.push_status_not_configured)
            PushSetupState.Registering -> stringResource(R.string.push_status_registering)
            is PushSetupState.Error -> PushUiError.fromCode(state.message).text()
        }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (state is PushSetupState.Error) MaterialTheme.colorScheme.error else iconTint(active),
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.push_status_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onManageClick) {
                Text(stringResource(if (active) R.string.push_status_manage else R.string.push_status_setup))
            }
        }
    }
}

@Composable
private fun iconTint(active: Boolean) =
    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@Preview
@Composable
private fun PushStatusCardActivePreview() {
    KumaTheme(dynamicColor = false) {
        PushStatusCardContent(state = PushSetupState.Active, onManageClick = {})
    }
}

@Preview(locale = "de")
@Composable
private fun PushStatusCardErrorPreview() {
    KumaTheme(dynamicColor = false) {
        PushStatusCardContent(state = PushSetupState.Error("ENDPOINT_TIMEOUT"), onManageClick = {})
    }
}
