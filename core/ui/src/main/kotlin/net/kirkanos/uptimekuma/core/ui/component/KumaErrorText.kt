package net.kirkanos.uptimekuma.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.ui.R
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme

/** String resource describing an error [KumaError.Kind]. */
@StringRes
fun KumaError.Kind.messageRes(): Int =
    when (this) {
        KumaError.Kind.NETWORK -> R.string.core_ui_error_network
        KumaError.Kind.INVALID_CREDENTIALS -> R.string.core_ui_error_invalid_credentials
        KumaError.Kind.RATE_LIMITED -> R.string.core_ui_error_rate_limited
        KumaError.Kind.UNSUPPORTED_SERVER -> R.string.core_ui_error_unsupported_server
        KumaError.Kind.SETUP_REQUIRED -> R.string.core_ui_error_setup_required
        KumaError.Kind.SERVER_REJECTED -> R.string.core_ui_error_server_rejected
        KumaError.Kind.TIMEOUT -> R.string.core_ui_error_timeout
        KumaError.Kind.UNAUTHORIZED -> R.string.core_ui_error_unauthorized
        KumaError.Kind.UNKNOWN -> R.string.core_ui_error_unknown
    }

/**
 * Localized message for this error. Server rejections carry Kuma's own (untranslated) text, which is
 * more useful than a generic sentence, so it is appended when present.
 */
@Composable
fun KumaError.localizedMessage(): String {
    val base = stringResource(kind.messageRes())
    val detail = message?.takeIf { it.isNotBlank() }
    return if (kind == KumaError.Kind.SERVER_REJECTED && detail != null) {
        stringResource(R.string.core_ui_error_with_detail, base, detail)
    } else {
        base
    }
}

/** A [Text] showing the localized [error] message, in the error color by default. */
@Composable
fun KumaErrorText(
    error: KumaError,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.error,
) {
    Text(text = error.localizedMessage(), modifier = modifier, style = style, color = color)
}

@Preview(showBackground = true)
@Composable
private fun KumaErrorTextPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KumaError.Kind.entries.forEach { kind ->
                    KumaErrorText(KumaError(kind, message = "Monitor not found"))
                }
            }
        }
    }
}
