package net.kirkanos.uptimekuma.feature.maintenance

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.model.Maintenance
import net.kirkanos.uptimekuma.core.model.MaintenanceMonthDay
import net.kirkanos.uptimekuma.core.model.MaintenanceStatus
import net.kirkanos.uptimekuma.core.model.MaintenanceStrategy
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.format.formatTime
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.core.ui.theme.statusColors
import net.kirkanos.uptimekuma.feature.maintenance.list.MaintenanceGroup
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@StringRes
internal fun MaintenanceStatus.labelRes(): Int =
    when (this) {
        MaintenanceStatus.UNDER_MAINTENANCE -> R.string.maintenance_status_under
        MaintenanceStatus.SCHEDULED -> R.string.maintenance_status_scheduled
        MaintenanceStatus.INACTIVE -> R.string.maintenance_status_inactive
        MaintenanceStatus.ENDED -> R.string.maintenance_status_ended
        MaintenanceStatus.UNKNOWN -> R.string.maintenance_status_unknown
    }

@StringRes
internal fun MaintenanceGroup.titleRes(): Int =
    when (this) {
        MaintenanceGroup.ACTIVE -> R.string.maintenance_group_active
        MaintenanceGroup.SCHEDULED -> R.string.maintenance_group_scheduled
        MaintenanceGroup.PAUSED -> R.string.maintenance_group_paused
        MaintenanceGroup.ENDED -> R.string.maintenance_group_ended
        MaintenanceGroup.UNKNOWN -> R.string.maintenance_group_unknown
    }

@StringRes
internal fun MaintenanceStrategy.labelRes(): Int =
    when (this) {
        MaintenanceStrategy.MANUAL -> R.string.maintenance_strategy_manual
        MaintenanceStrategy.SINGLE -> R.string.maintenance_strategy_single
        MaintenanceStrategy.CRON -> R.string.maintenance_strategy_cron
        MaintenanceStrategy.RECURRING_INTERVAL -> R.string.maintenance_strategy_interval
        MaintenanceStrategy.RECURRING_WEEKDAY -> R.string.maintenance_strategy_weekday
        MaintenanceStrategy.RECURRING_DAY_OF_MONTH -> R.string.maintenance_strategy_day_of_month
    }

/** Web UI colors: under maintenance = maintenance blue, scheduled = primary, paused/ended/unknown = greys. */
@Composable
@ReadOnlyComposable
internal fun maintenanceStatusColor(status: MaintenanceStatus): Color =
    when (status) {
        MaintenanceStatus.UNDER_MAINTENANCE -> MaterialTheme.statusColors.maintenance
        MaintenanceStatus.SCHEDULED -> MaterialTheme.colorScheme.primary
        MaintenanceStatus.INACTIVE -> MaterialTheme.statusColors.paused
        MaintenanceStatus.ENDED, MaintenanceStatus.UNKNOWN -> MaterialTheme.statusColors.unknown
    }

/** Pill with a colored dot and the localized status, styled like core:ui's StatusChip. */
@Composable
internal fun MaintenanceStatusChip(
    status: MaintenanceStatus,
    modifier: Modifier = Modifier,
) {
    val color = maintenanceStatusColor(status)
    val label = stringResource(status.labelRes())
    val description = stringResource(R.string.maintenance_status_description, label)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = CircleShape,
        color = color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaintenanceStatusChipPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MaintenanceStatus.entries.forEach { MaintenanceStatusChip(it) }
                PreviewData.all.forEach { Text(scheduleSummary(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
