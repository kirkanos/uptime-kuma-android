package net.kirkanos.uptimekuma.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.ui.component.KumaErrorText
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme

/** Type-safe navigation route of the onboarding / login flow. */
@Serializable
data object OnboardingRoute

/**
 * Adds the onboarding destination. [onFinished] is called after a successful login; `reLogin` is true
 * when only an expired session was renewed (the caller should simply go back).
 */
fun NavGraphBuilder.onboardingScreen(onFinished: (reLogin: Boolean) -> Unit) {
    composable<OnboardingRoute> { LoginRoute(onFinished = onFinished) }
}

@Composable
internal fun LoginRoute(
    onFinished: (reLogin: Boolean) -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onFinished(false) }

    val canGoBack =
        state.step == LoginStep.TWO_FACTOR || (state.step == LoginStep.CREDENTIALS && !state.reLogin)
    BackHandler(enabled = canGoBack) { viewModel.back() }

    LaunchedEffect(state.step, state.reLogin) {
        if (state.step == LoginStep.DONE && state.reLogin) onFinished(true)
    }

    LoginScreen(
        state = state,
        onServerUrlChange = viewModel::onServerUrlChange,
        onSubmitServerUrl = viewModel::submitServerUrl,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmitCredentials = viewModel::submitCredentials,
        onTotpChange = viewModel::onTotpChange,
        onSubmitTotp = viewModel::submitTotp,
        onBack = { viewModel.back() },
        onContinue = {
            val needsPermission =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onFinished(false)
            }
        },
    )
}

@Composable
internal fun LoginScreen(
    state: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onSubmitServerUrl: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmitCredentials: () -> Unit,
    onTotpChange: (String) -> Unit,
    onSubmitTotp: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AnimatedContent(targetState = state.step, label = "loginStep") { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StepHeader(step = step, state = state)
                        when (step) {
                            LoginStep.SERVER -> {
                                ServerStep(state, onServerUrlChange, onSubmitServerUrl)
                            }

                            LoginStep.CREDENTIALS -> {
                                CredentialsStep(
                                    state = state,
                                    onUsernameChange = onUsernameChange,
                                    onPasswordChange = onPasswordChange,
                                    onSubmit = onSubmitCredentials,
                                    onChangeServer = onBack,
                                )
                            }

                            LoginStep.TWO_FACTOR -> {
                                TwoFactorStep(state, onTotpChange, onSubmitTotp, onBack)
                            }

                            LoginStep.DONE -> {
                                DoneStep(state, onContinue)
                            }
                        }
                    }
                }
                state.error?.let { ErrorCard(error = it, baseUrl = state.baseUrl) }
            }
        }
    }
}

@Composable
private fun StepHeader(
    step: LoginStep,
    state: LoginUiState,
) {
    val icon =
        when (step) {
            LoginStep.SERVER -> Icons.Outlined.MonitorHeart
            LoginStep.CREDENTIALS -> Icons.Outlined.Lock
            LoginStep.TWO_FACTOR -> Icons.Outlined.Lock
            LoginStep.DONE -> Icons.Outlined.CheckCircle
        }
    val title =
        when (step) {
            LoginStep.SERVER -> R.string.onboarding_welcome_title
            LoginStep.CREDENTIALS -> R.string.onboarding_credentials_title
            LoginStep.TWO_FACTOR -> R.string.onboarding_two_factor_title
            LoginStep.DONE -> R.string.onboarding_done_title
        }
    val subtitle =
        when (step) {
            LoginStep.SERVER -> {
                stringResource(R.string.onboarding_server_subtitle)
            }

            LoginStep.CREDENTIALS -> {
                if (state.reLogin) {
                    stringResource(R.string.onboarding_relogin_subtitle)
                } else {
                    stringResource(R.string.onboarding_credentials_subtitle)
                }
            }

            LoginStep.TWO_FACTOR -> {
                stringResource(R.string.onboarding_two_factor_subtitle)
            }

            LoginStep.DONE -> {
                stringResource(R.string.onboarding_done_subtitle, state.baseUrl.orEmpty())
            }
        }
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp),
        )
    }
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerStep(
    state: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val errorText =
        when (state.urlError) {
            ServerUrl.Reason.EMPTY -> stringResource(R.string.onboarding_url_error_empty)
            ServerUrl.Reason.INVALID -> stringResource(R.string.onboarding_url_error_invalid)
            ServerUrl.Reason.UNSUPPORTED_SCHEME -> stringResource(R.string.onboarding_url_error_scheme)
            null -> null
        }
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = onServerUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.onboarding_server_url_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_server_url_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
    )
    Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.onboarding_continue))
    }
}

@Composable
private fun CredentialsStep(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Dns,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.baseUrl.orEmpty(),
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!state.reLogin) {
            TextButton(onClick = onChangeServer, enabled = !state.busy) {
                Text(stringResource(R.string.onboarding_change_server))
            }
        }
    }
    if (state.insecure) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.onboarding_insecure_warning),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    OutlinedTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        label = { Text(stringResource(R.string.onboarding_username)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        label = { Text(stringResource(R.string.onboarding_password)) },
        singleLine = true,
        enabled = !state.busy,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription =
                        stringResource(
                            if (passwordVisible) R.string.onboarding_hide_password else R.string.onboarding_show_pw,
                        ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
    ProgressButton(
        text = stringResource(R.string.onboarding_login),
        busy = state.busy,
        enabled = state.canSubmitCredentials,
        onClick = onSubmit,
    )
}

@Composable
private fun TwoFactorStep(
    state: LoginUiState,
    onTotpChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    OutlinedTextField(
        value = state.totp,
        onValueChange = onTotpChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
        label = { Text(stringResource(R.string.onboarding_two_factor_label)) },
        singleLine = true,
        enabled = !state.busy,
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
    ProgressButton(
        text = stringResource(R.string.onboarding_verify),
        busy = state.busy,
        enabled = state.canSubmitTotp,
        onClick = onSubmit,
    )
    OutlinedButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_back))
    }
}

@Composable
private fun DoneStep(
    state: LoginUiState,
    onContinue: () -> Unit,
) {
    if (!state.reLogin) {
        Text(
            text = stringResource(R.string.onboarding_done_next),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.onboarding_continue))
    }
}

@Composable
private fun ProgressButton(
    text: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val busyDescription = stringResource(R.string.onboarding_logging_in)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(20.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                strokeWidth = 2.dp,
            )
            Text(text = busyDescription, modifier = Modifier.padding(start = 12.dp))
        } else {
            Text(text)
        }
    }
}

@Composable
private fun ErrorCard(
    error: KumaError,
    baseUrl: String?,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error.kind == KumaError.Kind.SETUP_REQUIRED) {
                Text(
                    text = stringResource(R.string.onboarding_setup_required_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            KumaErrorText(error = error, color = MaterialTheme.colorScheme.onErrorContainer)
            if (error.kind == KumaError.Kind.SETUP_REQUIRED && baseUrl != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(baseUrl) } }) {
                    Text(stringResource(R.string.onboarding_open_web_ui))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerStepPreview() {
    KumaTheme(dynamicColor = false) {
        LoginScreen(
            state = LoginUiState(serverUrl = "uptime.example", urlError = ServerUrl.Reason.INVALID),
            onServerUrlChange = {},
            onSubmitServerUrl = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmitCredentials = {},
            onTotpChange = {},
            onSubmitTotp = {},
            onBack = {},
            onContinue = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CredentialsStepPreview() {
    KumaTheme(dynamicColor = false) {
        LoginScreen(
            state =
                LoginUiState(
                    step = LoginStep.CREDENTIALS,
                    baseUrl = "http://192.168.1.10:3001",
                    insecure = true,
                    username = "admin",
                    password = "secret",
                    error = KumaError(KumaError.Kind.SETUP_REQUIRED),
                ),
            onServerUrlChange = {},
            onSubmitServerUrl = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmitCredentials = {},
            onTotpChange = {},
            onSubmitTotp = {},
            onBack = {},
            onContinue = {},
        )
    }
}

@Preview(showBackground = true, fontScale = 1.5f)
@Composable
private fun TwoFactorStepPreview() {
    KumaTheme(dynamicColor = false) {
        LoginScreen(
            state = LoginUiState(step = LoginStep.TWO_FACTOR, baseUrl = "https://uptime.example.org", totp = "123"),
            onServerUrlChange = {},
            onSubmitServerUrl = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmitCredentials = {},
            onTotpChange = {},
            onSubmitTotp = {},
            onBack = {},
            onContinue = {},
        )
    }
}
