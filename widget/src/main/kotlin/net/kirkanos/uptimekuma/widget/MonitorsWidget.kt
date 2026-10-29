package net.kirkanos.uptimekuma.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.widget.di.WidgetEntryPoint
import net.kirkanos.uptimekuma.widget.state.MonitorsWidgetState
import net.kirkanos.uptimekuma.widget.state.OverallStatus
import net.kirkanos.uptimekuma.widget.state.WidgetMonitorRow
import java.util.Date
import kotlin.math.roundToInt

/**
 * Home-screen widget with two responsive layouts: a compact overall status ([SMALL]) and a list with the
 * problem monitors first ([LIST]). Data comes from the monitor cache via `WidgetStateHolder` (works offline);
 * redraws are pushed by `WidgetUpdater`, the widget
 * never polls on its own. Colors follow the (dynamic) system theme, light and dark.
 */
class MonitorsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, LIST))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val holder =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetStateHolder()
        val initial = holder.current()
        provideContent {
            val published by holder.state.collectAsState()
            GlanceTheme {
                MonitorsWidgetContent(published ?: initial)
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 48.dp)
        val LIST = DpSize(200.dp, 110.dp)
    }
}

class MonitorsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonitorsWidget()
}

private val HEADER_HEIGHT = 28.dp
private val ROW_HEIGHT = 26.dp
private val PADDING = 12.dp
private const val MAX_ROWS = 8

@Composable
private fun MonitorsWidgetContent(state: MonitorsWidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val openApp = launchAction(context, monitorId = null)
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(16.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(PADDING),
    ) {
        if (size.width < MonitorsWidget.LIST.width || size.height < MonitorsWidget.LIST.height) {
            SummaryLayout(state, openApp)
        } else {
            ListLayout(state, openApp, availableHeight = size.height.value - 2 * PADDING.value)
        }
    }
}

@Composable
private fun SummaryLayout(
    state: MonitorsWidgetState,
    openApp: Action?,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxSize().clickableIfNotNull(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(overallColor(state.overall), 16)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                text = overallText(context, state),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = secondaryText(context, state),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ListLayout(
    state: MonitorsWidgetState,
    openApp: Action?,
    availableHeight: Float,
) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(state, openApp)
        when (state.overall) {
            OverallStatus.NOT_CONFIGURED, OverallStatus.NO_MONITORS -> {
                Text(
                    text = secondaryText(context, state),
                    modifier = GlanceModifier.padding(top = 8.dp).clickableIfNotNull(openApp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }

            else -> {
                val capacity =
                    ((availableHeight - HEADER_HEIGHT.value) / ROW_HEIGHT.value).toInt().coerceIn(1, MAX_ROWS)
                val overflow = state.rows.size > capacity
                val shown = state.rows.take(if (overflow) capacity - 1 else capacity)
                shown.forEach { row -> MonitorRow(row) }
                if (overflow) {
                    val more = state.rows.size - shown.size
                    Text(
                        text = context.resources.getQuantityString(R.plurals.widget_more, more, more),
                        modifier = GlanceModifier.height(ROW_HEIGHT).padding(start = 18.dp).clickableIfNotNull(openApp),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: MonitorsWidgetState,
    openApp: Action?,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT).clickableIfNotNull(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(overallColor(state.overall), 12)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = overallText(context, state),
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        state.updatedAtMillis?.let { updated ->
            Text(
                text = context.getString(R.string.widget_updated, formatTime(context, updated)),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MonitorRow(row: WidgetMonitorRow) {
    val context = LocalContext.current
    val statusLabel = context.getString(statusLabelRes(row.status))
    val trailing =
        when {
            row.status == DisplayStatus.UP && row.pingMs != null -> {
                context.getString(R.string.widget_ping, row.pingMs.roundToInt())
            }

            row.lastBeatMillis != null -> {
                formatTime(context, row.lastBeatMillis)
            }

            else -> {
                ""
            }
        }
    Row(
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .clickableIfNotNull(launchAction(context, row.id))
                .semantics { contentDescription = "${row.name}, $statusLabel" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(statusColor(row.status), 8)
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = row.name,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            maxLines = 1,
        )
        Text(
            text = trailing,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusDot(
    color: ColorProvider,
    sizeDp: Int,
) {
    Box(
        modifier =
            GlanceModifier
                .size(sizeDp.dp)
                .cornerRadius((sizeDp / 2).dp)
                .background(color),
    ) {}
}

private fun GlanceModifier.clickableIfNotNull(action: Action?): GlanceModifier =
    if (action == null) this else clickable(action)

/** Opens the app (launcher activity), with [monitorId] as deep link when given. */
private fun launchAction(
    context: Context,
    monitorId: Long?,
): Action? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (monitorId != null) intent.putExtra(WidgetIntents.EXTRA_MONITOR_ID, monitorId)
    return actionStartActivity(intent)
}

private fun formatTime(
    context: Context,
    millis: Long,
): String =
    android.text.format.DateFormat
        .getTimeFormat(context)
        .format(Date(millis))

internal fun overallText(
    context: Context,
    state: MonitorsWidgetState,
): String {
    val res = context.resources
    return when (state.overall) {
        OverallStatus.NOT_CONFIGURED -> res.getString(R.string.widget_not_configured)
        OverallStatus.NO_MONITORS -> res.getString(R.string.widget_no_monitors)
        OverallStatus.ALL_UP -> res.getString(R.string.widget_all_up)
        OverallStatus.PENDING -> res.getQuantityString(R.plurals.widget_pending, state.pending, state.pending)
        OverallStatus.DOWN -> res.getQuantityString(R.plurals.widget_down, state.down, state.down)
    }
}

private fun secondaryText(
    context: Context,
    state: MonitorsWidgetState,
): String {
    val res = context.resources
    return when (state.overall) {
        OverallStatus.NOT_CONFIGURED -> res.getString(R.string.widget_not_configured_hint)
        OverallStatus.NO_MONITORS -> res.getString(R.string.widget_title)
        else -> res.getQuantityString(R.plurals.widget_monitor_count, state.active, state.active)
    }
}

private fun statusLabelRes(status: DisplayStatus): Int =
    when (status) {
        DisplayStatus.UP -> R.string.widget_status_up
        DisplayStatus.DOWN -> R.string.widget_status_down
        DisplayStatus.PENDING -> R.string.widget_status_pending
        DisplayStatus.MAINTENANCE -> R.string.widget_status_maintenance
        DisplayStatus.PAUSED -> R.string.widget_status_paused
        DisplayStatus.UNKNOWN -> R.string.widget_status_unknown
    }

// Status colors mirror core/ui's StatusColors (light / dark), which are not reachable from Glance.
private val Up = ColorProvider(day = Color(0xFF1E9E57), night = Color(0xFF5CDD8B))
private val Down = ColorProvider(day = Color(0xFFD7263D), night = Color(0xFFFF6B6B))
private val Pending = ColorProvider(day = Color(0xFFC77C02), night = Color(0xFFFFB547))
private val Maintenance = ColorProvider(day = Color(0xFF2F62E0), night = Color(0xFF7FA6FF))
private val Neutral = ColorProvider(day = Color(0xFF78828A), night = Color(0xFF9AA4AB))

private fun statusColor(status: DisplayStatus): ColorProvider =
    when (status) {
        DisplayStatus.UP -> Up
        DisplayStatus.DOWN -> Down
        DisplayStatus.PENDING -> Pending
        DisplayStatus.MAINTENANCE -> Maintenance
        DisplayStatus.PAUSED, DisplayStatus.UNKNOWN -> Neutral
    }

private fun overallColor(overall: OverallStatus): ColorProvider =
    when (overall) {
        OverallStatus.ALL_UP -> Up
        OverallStatus.DOWN -> Down
        OverallStatus.PENDING -> Pending
        OverallStatus.NOT_CONFIGURED, OverallStatus.NO_MONITORS -> Neutral
    }
