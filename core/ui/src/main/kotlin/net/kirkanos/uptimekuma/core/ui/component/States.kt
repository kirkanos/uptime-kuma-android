package net.kirkanos.uptimekuma.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme

/** Centered progress indicator for a whole screen or section. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.core_ui_loading)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

/** Icon, title, optional message and optional action, centered. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            // Heading, so TalkBack users can jump to it with the headings navigation.
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** Error variant of [EmptyState] showing a localized [KumaError] and a retry button. */
@Composable
fun ErrorState(
    error: KumaError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = stringResource(R.string.core_ui_error_title),
        message = error.localizedMessage(),
        icon = Icons.Outlined.CloudOff,
        actionLabel = stringResource(R.string.core_ui_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}

@Preview(showBackground = true, heightDp = 240)
@Composable
private fun LoadingStatePreview() {
    KumaTheme(dynamicColor = false) { Surface { LoadingState() } }
}

@Preview(showBackground = true, heightDp = 320)
@Composable
private fun EmptyStatePreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            EmptyState(title = "No monitors", message = "Add monitors in the web UI.", actionLabel = "Refresh") {}
        }
    }
}

@Preview(showBackground = true, heightDp = 320, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ErrorStatePreview() {
    KumaTheme(dynamicColor = false) {
        Surface { ErrorState(KumaError(KumaError.Kind.NETWORK), Modifier.height(320.dp)) {} }
    }
}
