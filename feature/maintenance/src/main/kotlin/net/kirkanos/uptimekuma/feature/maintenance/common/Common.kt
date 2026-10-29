package net.kirkanos.uptimekuma.feature.maintenance.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.maintenance.R
import net.kirkanos.uptimekuma.feature.maintenance.list.ActionResult
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceAction

/** Shows [result] once as a snackbar (own success texts instead of Kuma's i18n keys), then calls [onShown]. */
@Composable
internal fun ActionResultSnackbar(
    result: ActionResult?,
    hostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val message =
        when (result) {
            null -> null
            is ActionResult.Failed -> result.error.localizedMessage()
            is ActionResult.Done -> stringResource(result.action.doneRes())
        }
    LaunchedEffect(result) {
        if (message != null) {
            onShown()
            hostState.showSnackbar(message)
        }
    }
}

private fun MaintenanceAction.doneRes(): Int =
    when (this) {
        MaintenanceAction.PAUSE -> R.string.maintenance_done_paused
        MaintenanceAction.RESUME -> R.string.maintenance_done_resumed
        MaintenanceAction.DELETE -> R.string.maintenance_done_deleted
    }

/** Confirmation for pause (like the web UI) and delete. */
@Composable
internal fun ConfirmActionDialog(
    action: MaintenanceAction,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val delete = action == MaintenanceAction.DELETE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (delete) R.string.maintenance_delete_title else R.string.maintenance_pause_title))
        },
        text = {
            Text(
                stringResource(
                    if (delete) R.string.maintenance_delete_message else R.string.maintenance_pause_message,
                    title,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(if (delete) R.string.maintenance_delete else R.string.maintenance_pause),
                    color = if (delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.maintenance_cancel)) } },
    )
}

@Preview
@Composable
private fun ConfirmActionDialogPreview() {
    KumaTheme(dynamicColor = false) {
        ConfirmActionDialog(MaintenanceAction.DELETE, "Database upgrade", onConfirm = {}, onDismiss = {})
    }
}
