package net.kirkanos.uptimekuma.feature.notifications.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kirkanos.uptimekuma.core.domain.notification.NotificationDraft
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderFieldSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.component.KumaErrorText
import net.kirkanos.uptimekuma.core.ui.component.LoadingState
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.notifications.FormSection
import net.kirkanos.uptimekuma.feature.notifications.PreviewData
import net.kirkanos.uptimekuma.feature.notifications.ProviderLayouts
import net.kirkanos.uptimekuma.feature.notifications.ProviderTexts
import net.kirkanos.uptimekuma.feature.notifications.R
import net.kirkanos.uptimekuma.feature.notifications.icon
import net.kirkanos.uptimekuma.feature.notifications.labelRes

internal enum class CommonFlag { DEFAULT, APPLY_EXISTING }

@Immutable
internal class NotificationEditActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    /** "Default enabled" / "Apply on all existing monitors" switched. */
    val onFlag: (CommonFlag, Boolean) -> Unit = { _, _ -> },
    val onValue: (String, Any?) -> Unit = { _, _ -> },
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onTest: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    /** The test result dialog or the save/delete error was dismissed. */
    val onMessageShown: () -> Unit = {},
)

@Composable
internal fun NotificationEditEntry(
    onDone: () -> Unit,
    viewModel: NotificationEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val actions =
        remember(viewModel, onDone) {
            NotificationEditActions(
                onBack = onDone,
                onName = viewModel::setName,
                onFlag = { flag, on ->
                    when (flag) {
                        CommonFlag.DEFAULT -> viewModel.setDefault(on)
                        CommonFlag.APPLY_EXISTING -> viewModel.setApplyExisting(on)
                    }
                },
                onValue = viewModel::setValue,
                onToggle = viewModel::setToggle,
                onTest = viewModel::test,
                onSave = viewModel::save,
                onDelete = viewModel::delete,
                onMessageShown = viewModel::consumeMessages,
            )
        }
    NotificationEditScreen(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationEditScreen(
    state: NotificationEditUiState,
    actions: NotificationEditActions,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.notif_edit_title_new else R.string.notif_edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.notif_back))
                    }
                },
                actions = {
                    if (draft != null && !draft.isNew) {
                        IconButton(onClick = { confirmDelete = true }, enabled = state.busy == null) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.notif_action_delete))
                        }
                    }
                },
            )
        },
        bottomBar = { if (draft != null) BottomActions(state, actions) },
    ) { padding ->
        val contentModifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.loading -> {
                LoadingState(contentModifier)
            }

            draft == null || state.notFound -> {
                EmptyState(
                    title = stringResource(R.string.notif_not_found),
                    modifier = contentModifier,
                    actionLabel = stringResource(R.string.notif_back),
                    onAction = actions.onBack,
                )
            }

            else -> {
                EditForm(state, draft, actions, contentModifier)
            }
        }
    }

    if (confirmDelete && draft != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.notif_delete_title)) },
            text = { Text(stringResource(R.string.notif_delete_message, draft.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete()
                }) { Text(stringResource(R.string.notif_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.notif_cancel)) }
            },
        )
    }
    state.testResult?.let { TestResultDialog(it, actions.onMessageShown) }
}

@Composable
private fun BottomActions(
    state: NotificationEditUiState,
    actions: NotificationEditActions,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            state.actionError?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KumaErrorText(error, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onMessageShown) { Text(stringResource(R.string.notif_ok)) }
                }
            }
            if (state.showErrors && state.issues.isNotEmpty()) {
                Text(
                    stringResource(R.string.notif_validation_summary),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = actions.onTest,
                    enabled = state.busy == null && state.provider?.configurable != false,
                    modifier = Modifier.weight(1f),
                ) {
                    BusyIcon(state.busy == Busy.TESTING, Icons.AutoMirrored.Outlined.Send)
                    Text(stringResource(R.string.notif_action_test))
                }
                Button(onClick = actions.onSave, enabled = state.busy == null, modifier = Modifier.weight(1f)) {
                    if (state.busy == Busy.SAVING || state.busy == Busy.DELETING) BusyIcon(true, null)
                    Text(stringResource(R.string.notif_action_save))
                }
            }
        }
    }
}

@Composable
private fun BusyIcon(
    busy: Boolean,
    icon: ImageVector?,
) {
    when {
        busy -> CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
        icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
    }
}

@Composable
private fun EditForm(
    state: NotificationEditUiState,
    draft: NotificationDraft,
    actions: NotificationEditActions,
    modifier: Modifier = Modifier,
) {
    val provider = state.provider
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.isThisDevice) {
            Banner(Icons.Outlined.PhoneAndroid, stringResource(R.string.notif_this_device_warning))
        }
        ProviderHeader(provider, draft.type)
        if (state.appriseAvailable == false) {
            Banner(Icons.Outlined.WarningAmber, stringResource(R.string.notif_apprise_missing))
        }
        CommonSection(state, draft, actions)
        if (provider == null) {
            InfoText(stringResource(R.string.notif_unknown_provider_kept))
        } else {
            ProviderLayouts.sections(provider).forEach { section ->
                ProviderSection(provider, section, state, draft, actions)
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProviderHeader(
    provider: ProviderSpec?,
    type: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Icon(provider.icon, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                provider?.label ?: stringResource(R.string.notif_unknown_provider, type),
                style = MaterialTheme.typography.titleMedium,
            )
            provider?.categories?.let { categories ->
                Text(
                    categories.map { stringResource(it.labelRes()) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    provider?.let { ProviderTexts.intro[it.id] }?.let { InfoText(stringResource(it)) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun CommonSection(
    state: NotificationEditUiState,
    draft: NotificationDraft,
    actions: NotificationEditActions,
) {
    SectionTitle(stringResource(R.string.notif_section_general))
    TextInput(
        label = stringResource(R.string.notif_required_label, stringResource(R.string.notif_common_name)),
        value = draft.name,
        issue = state.issueFor(NotificationForm.NAME),
        onValue = { actions.onName(it as String) },
    )
    SwitchRow(
        label = stringResource(R.string.notif_common_default),
        checked = draft.isDefault,
        onValue = { actions.onFlag(CommonFlag.DEFAULT, it) },
        help = stringResource(R.string.notif_common_default_help),
    )
    if (draft.isNew) {
        SwitchRow(
            label = stringResource(R.string.notif_common_apply_existing),
            checked = draft.applyExisting,
            onValue = { actions.onFlag(CommonFlag.APPLY_EXISTING, it) },
            help = stringResource(R.string.notif_common_apply_existing_help),
        )
    }
    if (!draft.active) InfoText(stringResource(R.string.notif_common_inactive))
}

@Composable
private fun ProviderSection(
    provider: ProviderSpec,
    section: FormSection,
    state: NotificationEditUiState,
    draft: NotificationDraft,
    actions: NotificationEditActions,
) {
    var expanded by rememberSaveable(section.title) { mutableStateOf(!section.collapsed) }
    val title = stringResource(section.title ?: R.string.notif_section_settings)
    if (section.collapsed) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f)) { SectionTitle(title) }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.notif_collapse else R.string.notif_expand),
            )
        }
    } else {
        SectionTitle(title)
    }
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shownToggles = mutableSetOf<String>()
            section.fields.forEach { field ->
                // A UI-only switch (e.g. "additional headers") goes right before the first field it reveals.
                val toggles = field.visibility.identifiers().filter { it in provider.uiToggles && shownToggles.add(it) }
                toggles.forEach { toggle -> ToggleRow(toggle, draft, actions) }
                if (NotificationForm.isVisible(field, draft)) {
                    FieldRow(provider, field, state, draft, actions)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    toggle: String,
    draft: NotificationDraft,
    actions: NotificationEditActions,
) {
    SwitchRow(
        label = ProviderTexts.toggles[toggle]?.let { stringResource(it) } ?: toggle,
        checked = draft.toggles[toggle] == true,
        onValue = { actions.onToggle(toggle, it) },
    )
}

@Composable
private fun FieldRow(
    provider: ProviderSpec,
    field: ProviderFieldSpec,
    state: NotificationEditUiState,
    draft: NotificationDraft,
    actions: NotificationEditActions,
) {
    ProviderField(
        provider = provider,
        field = field,
        value = draft.values[field.name],
        issue = state.issueFor(field.name),
        onValue = { actions.onValue(field.name, it) },
    )
}

@Composable
private fun TestResultDialog(
    result: TestResult,
    onDismiss: () -> Unit,
) {
    val (title, text) =
        when (result) {
            is TestResult.Success -> {
                stringResource(R.string.notif_test_success_title) to
                    when (val message = result.message) {
                        null, "" -> stringResource(R.string.notif_test_sent_successfully)
                        SENT_SUCCESSFULLY -> stringResource(R.string.notif_test_sent_successfully)
                        else -> stringResource(R.string.notif_test_kuma_says, message)
                    }
            }

            is TestResult.Failure -> {
                stringResource(R.string.notif_test_failed_title) to result.error.localizedMessage()
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notif_ok)) } },
    )
}

/** Kuma's generic provider success message (not an i18n key). */
private const val SENT_SUCCESSFULLY = "Sent Successfully."

private fun previewState(
    notification: KumaNotification?,
    type: String = "smtp",
): NotificationEditUiState {
    val spec = NotificationProviderSpec.bundled
    val provider = spec.provider(notification?.type ?: type)
    val draft =
        if (notification == null) {
            NotificationForm.newDraft(requireNotNull(provider))
        } else {
            NotificationForm.fromNotification(notification, provider)
        }
    return NotificationEditUiState(
        loading = false,
        provider = provider,
        draft = draft,
        issues = NotificationForm.validate(provider, draft),
        isThisDevice = notification?.let(NotificationForm::isThisDevicePush) == true,
    )
}

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun NotificationEditSmtpPreview() {
    KumaTheme(dynamicColor = false) {
        NotificationEditScreen(previewState(null).copy(showErrors = true), NotificationEditActions())
    }
}

@Preview(showBackground = true, heightDp = 1200, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotificationEditDevicePreview() {
    KumaTheme(darkTheme = true, dynamicColor = false) {
        NotificationEditScreen(previewState(PreviewData.device), NotificationEditActions())
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun NotificationEditGenericPreview() {
    KumaTheme(dynamicColor = false) {
        NotificationEditScreen(
            previewState(null, type = "SMSEagle").copy(actionError = KumaError(KumaError.Kind.NETWORK)),
            NotificationEditActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TestResultPreview() {
    KumaTheme(dynamicColor = false) {
        TestResultDialog(
            TestResult.Failure(
                KumaError(KumaError.Kind.SERVER_REJECTED, "Invalid login: 535 Authentication failed"),
            ),
            {
            },
        )
    }
}
