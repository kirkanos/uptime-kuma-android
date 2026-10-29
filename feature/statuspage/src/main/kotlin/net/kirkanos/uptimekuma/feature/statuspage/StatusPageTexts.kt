package net.kirkanos.uptimekuma.feature.statuspage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.OverallStatus
import net.kirkanos.uptimekuma.core.model.StatusPageAnalytics
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.core.model.StatusPageTheme
import net.kirkanos.uptimekuma.core.ui.component.localizedMessage
import net.kirkanos.uptimekuma.core.ui.theme.statusColors

private const val TINT_ALPHA = 0.22f

/** Kuma's (untranslated) status page messages mapped to own strings; everything else via [localizedMessage]. */
@Composable
fun statusPageErrorText(error: KumaError): String {
    val message = error.message.orEmpty()
    val res =
        when {
            message.contains("UNIQUE constraint", ignoreCase = true) -> {
                R.string.statuspage_error_slug_taken
            }

            message == "Status Page Not Found" || message == "No slug?" || message == "slug is not found" -> {
                R.string.statuspage_error_not_found
            }

            message == "Invalid Slug" || message == "Slug cannot be empty" -> {
                R.string.statuspage_error_invalid_slug
            }

            message == "Only allowed PNG logo." -> {
                R.string.statuspage_error_png
            }

            message == "Invalid analytics type" -> {
                R.string.statuspage_error_analytics
            }

            else -> {
                null
            }
        }
    return res?.let { stringResource(it) } ?: error.localizedMessage()
}

@Composable
fun slugProblemText(problem: StatusPageSlug.Problem): String =
    stringResource(
        when (problem) {
            StatusPageSlug.Problem.EMPTY -> R.string.statuspage_slug_empty
            StatusPageSlug.Problem.INVALID_CHARACTERS -> R.string.statuspage_slug_invalid_chars
            StatusPageSlug.Problem.CONSECUTIVE_OR_EDGE_DASH -> R.string.statuspage_slug_dashes
        },
    )

@Composable
fun incidentStyleLabel(style: IncidentStyle): String =
    stringResource(
        when (style) {
            IncidentStyle.INFO -> R.string.statuspage_style_info
            IncidentStyle.WARNING -> R.string.statuspage_style_warning
            IncidentStyle.DANGER -> R.string.statuspage_style_danger
            IncidentStyle.PRIMARY -> R.string.statuspage_style_primary
            IncidentStyle.LIGHT -> R.string.statuspage_style_light
            IncidentStyle.DARK -> R.string.statuspage_style_dark
        },
    )

/** Background and content colour of an incident card, following the Bootstrap meaning of the style. */
@Composable
fun incidentColors(style: IncidentStyle): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.statusColors
    return when (style) {
        IncidentStyle.INFO -> {
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        }

        IncidentStyle.WARNING -> {
            status.pending.copy(alpha = TINT_ALPHA).compositeOver(scheme.surface) to
                scheme.onSurface
        }

        IncidentStyle.DANGER -> {
            scheme.errorContainer to scheme.onErrorContainer
        }

        IncidentStyle.PRIMARY -> {
            scheme.primaryContainer to scheme.onPrimaryContainer
        }

        IncidentStyle.LIGHT -> {
            scheme.surfaceContainerHighest to scheme.onSurface
        }

        IncidentStyle.DARK -> {
            scheme.inverseSurface to scheme.inverseOnSurface
        }
    }
}

@Composable
fun overallStatusLabel(status: OverallStatus): String =
    stringResource(
        when (status) {
            OverallStatus.ALL_UP -> R.string.statuspage_overall_all_up
            OverallStatus.PARTIAL_DOWN -> R.string.statuspage_overall_partial
            OverallStatus.ALL_DOWN -> R.string.statuspage_overall_all_down
            OverallStatus.MAINTENANCE -> R.string.statuspage_overall_maintenance
            OverallStatus.UNKNOWN -> R.string.statuspage_overall_unknown
        },
    )

@Composable
fun overallStatusColor(status: OverallStatus): Color {
    val colors = MaterialTheme.statusColors
    return when (status) {
        OverallStatus.ALL_UP -> colors.up
        OverallStatus.PARTIAL_DOWN -> colors.pending
        OverallStatus.ALL_DOWN -> colors.down
        OverallStatus.MAINTENANCE -> colors.maintenance
        OverallStatus.UNKNOWN -> colors.unknown
    }
}

@Composable
fun themeLabel(theme: StatusPageTheme): String =
    stringResource(
        when (theme) {
            StatusPageTheme.AUTO -> R.string.statuspage_theme_auto
            StatusPageTheme.LIGHT -> R.string.statuspage_theme_light
            StatusPageTheme.DARK -> R.string.statuspage_theme_dark
        },
    )

/** Product names, identical in all languages. */
fun analyticsLabel(type: StatusPageAnalytics): String =
    when (type) {
        StatusPageAnalytics.GOOGLE -> "Google"
        StatusPageAnalytics.UMAMI -> "Umami"
        StatusPageAnalytics.PLAUSIBLE -> "Plausible"
        StatusPageAnalytics.MATOMO -> "Matomo"
        StatusPageAnalytics.RYBBIT -> "Rybbit"
    }

/** Monitor types whose URL can be shown as a link (web UI `showLink` in edit mode). */
fun supportsLink(type: String?): Boolean = type == "http" || type == "keyword" || type == "json-query"
