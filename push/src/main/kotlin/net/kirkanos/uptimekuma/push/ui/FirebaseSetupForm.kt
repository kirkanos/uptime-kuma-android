package net.kirkanos.uptimekuma.push.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.FirebaseConfigField
import net.kirkanos.uptimekuma.core.model.PushTransport
import net.kirkanos.uptimekuma.push.R
import net.kirkanos.uptimekuma.push.firebase.RelayUrl

/** Short name of a transport for running text ("Active via %s"). */
@Composable
fun PushTransport.shortLabel(): String =
    when (this) {
        PushTransport.NTFY -> stringResource(R.string.push_transport_short_ntfy)
        PushTransport.FIREBASE -> stringResource(R.string.push_transport_short_firebase)
    }

/** The first step in the `full` flavor: ntfy or Firebase. */
@Composable
internal fun TransportPicker(
    state: PushSetupUiState,
    onSelected: (PushTransport) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.push_transport_title), style = MaterialTheme.typography.titleMedium)
        listOf(PushTransport.NTFY, PushTransport.FIREBASE)
            .filter { it in state.transports }
            .forEach { transport ->
                TransportOption(
                    icon = if (transport == PushTransport.NTFY) Icons.Outlined.Hub else Icons.Outlined.Cloud,
                    title =
                        stringResource(
                            if (transport == PushTransport.NTFY) {
                                R.string.push_transport_ntfy
                            } else {
                                R.string.push_transport_firebase
                            },
                        ),
                    text =
                        stringResource(
                            if (transport == PushTransport.NTFY) {
                                R.string.push_transport_ntfy_text
                            } else {
                                R.string.push_transport_firebase_text
                            },
                        ),
                    selected = transport == state.transport,
                    onClick = { onSelected(transport) },
                )
            }
    }
}

@Composable
private fun TransportOption(
    icon: ImageVector,
    title: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors =
            if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Header of a form: which transport, and (when the build has several) a way back to the picker. */
@Composable
internal fun TransportHeader(
    state: PushSetupUiState,
    onChooseTransport: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text =
                stringResource(
                    if (state.transport == PushTransport.NTFY) {
                        R.string.push_transport_ntfy
                    } else {
                        R.string.push_transport_firebase
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (state.canChooseTransport) {
            TextButton(onClick = onChooseTransport) { Text(stringResource(R.string.push_transport_change)) }
        }
    }
}

/** Relay, Firebase project values (import or manual), "apply to all", set up. */
@Composable
internal fun FirebaseSetupForm(
    state: PushSetupUiState,
    packageName: String,
    actions: PushSetupActions,
) {
    val form = state.firebase
    RelaySection(form, actions)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.push_firebase_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.push_firebase_help), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedButton(onClick = actions.onImportGoogleServices) {
            Icon(imageVector = Icons.Outlined.FileOpen, contentDescription = null)
            Text(text = stringResource(R.string.push_firebase_import), modifier = Modifier.padding(start = 8.dp))
        }
        if (state.firebaseImported) {
            Text(
                text = stringResource(R.string.push_firebase_imported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val invalid = form.invalidFields
        FirebaseConfigField.entries.forEach { field ->
            OutlinedTextField(
                value = form.value(field),
                onValueChange = { actions.onFirebaseFieldChange(field, it) },
                label = { Text(field.label()) },
                supportingText = {
                    Text(
                        stringResource(if (field in invalid) R.string.push_firebase_invalid_value else field.hint()),
                    )
                },
                isError = field in invalid,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (field ==
                                FirebaseConfigField.SENDER_ID
                            ) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Ascii
                            },
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    ApplyToAllRow(state.applyToAll, actions.onApplyToAllChange)
    Button(onClick = actions.onSetUp, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.push_setup_button))
    }
}

@Composable
private fun RelaySection(
    form: FirebaseFormState,
    actions: PushSetupActions,
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.push_relay_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.push_relay_help), style = MaterialTheme.typography.bodyMedium)
        val check = form.relayCheck
        OutlinedTextField(
            value = form.relayUrl,
            onValueChange = actions.onRelayUrlChange,
            label = { Text(stringResource(R.string.push_relay_url_label)) },
            placeholder = { Text("https://relay.example.com") },
            singleLine = true,
            isError = check == RelayUrl.Check.INVALID,
            supportingText =
                when (check) {
                    RelayUrl.Check.INVALID -> {
                        { Text(stringResource(R.string.push_relay_url_invalid)) }
                    }

                    RelayUrl.Check.INSECURE -> {
                        {
                            Text(
                                text = stringResource(R.string.push_relay_url_insecure),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }

                    else -> {
                        null
                    }
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.relayToken,
            onValueChange = actions.onRelayTokenChange,
            label = { Text(stringResource(R.string.push_relay_token_label)) },
            supportingText = { Text(stringResource(R.string.push_relay_token_help)) },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription =
                            stringResource(if (tokenVisible) R.string.push_token_hide else R.string.push_token_show),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun FirebaseConfigField.hint(): Int =
    when (this) {
        FirebaseConfigField.PROJECT_ID -> R.string.push_firebase_project_id_hint
        FirebaseConfigField.APPLICATION_ID -> R.string.push_firebase_app_id_hint
        FirebaseConfigField.API_KEY -> R.string.push_firebase_api_key_hint
        FirebaseConfigField.SENDER_ID -> R.string.push_firebase_sender_id_hint
    }
