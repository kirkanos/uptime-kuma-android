package net.kirkanos.uptimekuma.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.R
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import javax.inject.Inject
import net.kirkanos.uptimekuma.feature.statuspage.R as StatusPageR

/**
 * Minimal settings screen (server, push entry, logout). Lives in :app until :feature:settings is built out;
 * after logout the nav host notices the missing server config and returns to onboarding.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        val serverConfig: StateFlow<ServerConfig?> =
            sessionRepository
                .observeServerConfig()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        fun logout() {
            viewModelScope.launch { sessionRepository.logout() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPushSetup: () -> Unit,
    onStatusPages: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.serverConfig.collectAsStateWithLifecycle()
    SettingsContent(
        config = config,
        onBack = onBack,
        onPushSetup = onPushSetup,
        onLogout = viewModel::logout,
        onStatusPages = onStatusPages,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    config: ServerConfig?,
    onBack: () -> Unit,
    onPushSetup: () -> Unit,
    onLogout: () -> Unit,
    onStatusPages: () -> Unit = {},
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (config != null) {
                ListItem(
                    headlineContent = { Text(config.baseUrl) },
                    supportingContent = { Text(stringResource(R.string.settings_logged_in_as, config.username)) },
                    overlineContent = { Text(stringResource(R.string.settings_server)) },
                    leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                )
                HorizontalDivider()
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_push)) },
                supportingContent = { Text(stringResource(R.string.settings_push_summary)) },
                leadingContent = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPushSetup),
            )
            ListItem(
                headlineContent = { Text(stringResource(StatusPageR.string.statuspage_list_title)) },
                leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onStatusPages),
            )
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_logout),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable { confirmLogout = true },
            )
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        onLogout()
                    },
                ) { Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    KumaTheme(dynamicColor = false) {
        SettingsContent(
            config = ServerConfig(baseUrl = "https://uptime.example.org", username = "admin"),
            onBack = {},
            onPushSetup = {},
            onLogout = {},
        )
    }
}
