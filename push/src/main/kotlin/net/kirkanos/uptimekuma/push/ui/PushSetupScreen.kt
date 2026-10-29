package net.kirkanos.uptimekuma.push.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.push.R
import net.kirkanos.uptimekuma.push.notify.hasPostNotificationsPermission

private const val NTFY_FDROID_URL = "https://f-droid.org/packages/io.heckel.ntfy/"
private const val NTFY_PLAY_URL = "market://details?id=io.heckel.ntfy"
private const val NTFY_PLAY_WEB_URL = "https://play.google.com/store/apps/details?id=io.heckel.ntfy"
private const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com"

/** Push onboarding / management screen. [onDone] closes it. */
@Composable
fun PushSetupRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown as "Set up later" while push is not active (onboarding); `null` hides it. */
    onSkip: (() -> Unit)? = null,
    viewModel: PushSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted || context.notificationsAllowed()
        }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        notificationsAllowed = context.notificationsAllowed()
        onPauseOrDispose { }
    }
    PushSetupScreen(
        state = state,
        notificationsAllowed = notificationsAllowed,
        actions =
            PushSetupActions(
                onTokenChange = viewModel::onTokenChange,
                onApplyToAllChange = viewModel::onApplyToAllChange,
                onDistributorSelected = viewModel::onDistributorSelected,
                onSetUp = viewModel::setUp,
                onSendTest = viewModel::sendTest,
                onReconfigure = viewModel::reconfigure,
                onDisable = viewModel::disable,
                onRecheck = viewModel::refresh,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasPostNotificationsPermission(context)
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.openNotificationSettings()
                    }
                },
                onOpenUrl = { url -> context.openUrl(url) },
                onOpenDistributor = { pkg -> context.openApp(pkg) },
                onDone = onDone,
                onSkip = onSkip,
            ),
        modifier = modifier,
    )
}

/** Callbacks of [PushSetupScreen]. */
data class PushSetupActions(
    val onTokenChange: (String) -> Unit = {},
    val onApplyToAllChange: (Boolean) -> Unit = {},
    val onDistributorSelected: (String) -> Unit = {},
    val onSetUp: () -> Unit = {},
    val onSendTest: () -> Unit = {},
    val onReconfigure: () -> Unit = {},
    val onDisable: () -> Unit = {},
    val onRecheck: () -> Unit = {},
    val onRequestNotificationPermission: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    /** Opens the distributor app (package name); it must run once to start its connection service. */
    val onOpenDistributor: (String) -> Unit = {},
    val onDone: () -> Unit = {},
    val onSkip: (() -> Unit)? = null,
)

@Composable
fun PushSetupScreen(
    state: PushSetupUiState,
    notificationsAllowed: Boolean,
    actions: PushSetupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.push_setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.push_setup_intro), style = MaterialTheme.typography.bodyMedium)

        if (!notificationsAllowed) {
            InfoCard(
                icon = Icons.Outlined.NotificationsActive,
                title = stringResource(R.string.push_permission_title),
                text = stringResource(R.string.push_permission_text),
            ) {
                Button(onClick = actions.onRequestNotificationPermission) {
                    Text(stringResource(R.string.push_permission_button))
                }
            }
        }

        state.error?.let { ErrorCard(it) }

        when (state.phase) {
            PushSetupPhase.Loading -> Progress(text = null)
            PushSetupPhase.InstallDistributor -> InstallDistributorCard(actions)
            PushSetupPhase.Form -> SetupForm(state, actions)
            PushSetupPhase.Working -> Progress(text = stringResource(R.string.push_setup_working))
            PushSetupPhase.Active -> ActiveSection(state, actions)
        }

        val skip = actions.onSkip
        if (skip != null && state.phase != PushSetupPhase.Active) {
            TextButton(onClick = skip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.push_setup_later))
            }
        }

        BatteryHintCard(onOpenUrl = actions.onOpenUrl)
    }
}

@Composable
private fun InstallDistributorCard(actions: PushSetupActions) {
    InfoCard(
        icon = Icons.Outlined.Download,
        title = stringResource(R.string.push_install_title),
        text = stringResource(R.string.push_install_text),
    ) {
        Text(text = stringResource(R.string.push_install_steps), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.onOpenUrl(NTFY_FDROID_URL) }) {
                Text(stringResource(R.string.push_install_fdroid))
            }
            OutlinedButton(onClick = { actions.onOpenUrl(NTFY_PLAY_URL) }) {
                Text(stringResource(R.string.push_install_play))
            }
        }
        TextButton(onClick = actions.onRecheck) { Text(stringResource(R.string.push_install_recheck)) }
    }
}

@Composable
private fun SetupForm(
    state: PushSetupUiState,
    actions: PushSetupActions,
) {
    if (state.distributors.size > 1) {
        DistributorPicker(state, actions.onDistributorSelected)
    }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.push_token_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.token,
            onValueChange = actions.onTokenChange,
            label = { Text(stringResource(R.string.push_token_label)) },
            singleLine = true,
            isError = state.error == PushUiError.MissingToken,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription =
                            stringResource(
                                if (tokenVisible) R.string.push_token_hide else R.string.push_token_show,
                            ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.push_token_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "ntfy token add --label <device> kuma",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.push_apply_all), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.push_apply_all_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.applyToAll, onCheckedChange = actions.onApplyToAllChange)
    }
    Button(onClick = actions.onSetUp, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.push_setup_button))
    }
}

@Composable
private fun DistributorPicker(
    state: PushSetupUiState,
    onSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(text = stringResource(R.string.push_distributor_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.push_distributor_text), style = MaterialTheme.typography.bodyMedium)
        state.distributors.forEach { option ->
            val selected = option.packageName == state.selectedDistributor
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onSelected(option.packageName) },
                            role = Role.RadioButton,
                        ).padding(vertical = 4.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = option.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSection(
    state: PushSetupUiState,
    actions: PushSetupActions,
) {
    InfoCard(
        icon = Icons.Outlined.CheckCircle,
        title = stringResource(R.string.push_success_title),
        text = stringResource(R.string.push_success_text),
    ) {
        when (state.testState) {
            TestState.Idle -> {
                OutlinedButton(onClick = actions.onSendTest) { Text(stringResource(R.string.push_send_test)) }
            }

            TestState.Sending -> {
                Progress(text = stringResource(R.string.push_test_sending))
            }

            TestState.Sent -> {
                Text(text = stringResource(R.string.push_test_sent), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onSendTest) { Text(stringResource(R.string.push_send_test)) }
            }
        }
    }
    val distributor = state.selectedDistributor ?: state.distributors.firstOrNull()?.packageName
    if (distributor != null) {
        // A distributor registered from the background cannot start its foreground service
        // (Android 12+), so nothing is delivered until the user has opened it once.
        InfoCard(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.push_open_distributor_title),
            text = stringResource(R.string.push_open_distributor_text),
        ) {
            OutlinedButton(onClick = { actions.onOpenDistributor(distributor) }) {
                Text(stringResource(R.string.push_open_distributor_button))
            }
        }
    }
    Button(onClick = actions.onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.push_done)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onReconfigure) { Text(stringResource(R.string.push_reconfigure)) }
        TextButton(onClick = actions.onDisable) {
            Text(stringResource(R.string.push_disable), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BatteryHintCard(onOpenUrl: (String) -> Unit) {
    InfoCard(
        icon = Icons.Outlined.BatteryAlert,
        title = stringResource(R.string.push_battery_title),
        text = stringResource(R.string.push_battery_text),
    ) {
        TextButton(onClick = { onOpenUrl(DONT_KILL_MY_APP_URL) }) { Text(stringResource(R.string.push_battery_link)) }
    }
}

@Composable
private fun ErrorCard(error: PushUiError) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = error.text(), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    text: String,
    content: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun Progress(text: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        if (text != null) Text(text = text, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
    }
}

private fun Context.notificationsAllowed(): Boolean =
    hasPostNotificationsPermission(this) && NotificationManagerCompat.from(this).areNotificationsEnabled()

/** Notifications switched off in system settings (or permission permanently denied): open the app's page. */
private fun Context.openNotificationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing sensible left to open.
    }
}

private fun Context.openApp(packageName: String) {
    val intent =
        packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Distributor uninstalled in the meantime; the screen re-checks on resume.
    }
}

private fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No store app for market:// links: fall back to the web page.
        if (url == NTFY_PLAY_URL) openUrl(NTFY_PLAY_WEB_URL)
    }
}

private val previewDistributors =
    listOf(
        DistributorOption("io.heckel.ntfy", "ntfy"),
        DistributorOption("org.unifiedpush.distributor.nextpush", "NextPush"),
    )

@Preview(showBackground = true)
@Composable
private fun PushSetupInstallPreview() {
    KumaTheme(dynamicColor = false) {
        PushSetupScreen(
            state = PushSetupUiState(phase = PushSetupPhase.InstallDistributor),
            notificationsAllowed = false,
            actions = PushSetupActions(),
        )
    }
}

@Preview(showBackground = true, locale = "de")
@Composable
private fun PushSetupFormPreview() {
    KumaTheme(dynamicColor = false) {
        PushSetupScreen(
            state =
                PushSetupUiState(
                    phase = PushSetupPhase.Form,
                    distributors = previewDistributors,
                    selectedDistributor = "io.heckel.ntfy",
                    token = "tk_secret",
                ),
            notificationsAllowed = true,
            actions = PushSetupActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PushSetupErrorPreview() {
    KumaTheme(dynamicColor = false) {
        PushSetupScreen(
            state =
                PushSetupUiState(
                    phase = PushSetupPhase.Form,
                    distributors = previewDistributors.take(1),
                    selectedDistributor = "io.heckel.ntfy",
                    error = PushUiError.EndpointTimeout,
                ),
            notificationsAllowed = true,
            actions = PushSetupActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PushSetupActivePreview() {
    KumaTheme(dynamicColor = false) {
        PushSetupScreen(
            state = PushSetupUiState(phase = PushSetupPhase.Active, testState = TestState.Sent),
            notificationsAllowed = true,
            actions = PushSetupActions(),
        )
    }
}
