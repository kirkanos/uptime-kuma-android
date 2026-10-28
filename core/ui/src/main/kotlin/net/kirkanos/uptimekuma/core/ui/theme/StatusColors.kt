package net.kirkanos.uptimekuma.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import kotlin.math.abs
import kotlin.math.min

/** Semantic colors for monitor states. Provided by [KumaTheme], read via [MaterialTheme.statusColors]. */
@Immutable
data class StatusColors(
    val up: Color,
    val down: Color,
    val pending: Color,
    val maintenance: Color,
    val paused: Color,
    val unknown: Color,
    /** Color of heartbeat slots that have no beat yet. */
    val empty: Color,
) {
    fun forStatus(status: DisplayStatus): Color =
        when (status) {
            DisplayStatus.UP -> up
            DisplayStatus.DOWN -> down
            DisplayStatus.PENDING -> pending
            DisplayStatus.MAINTENANCE -> maintenance
            DisplayStatus.PAUSED -> paused
            DisplayStatus.UNKNOWN -> unknown
        }

    fun forBeat(status: MonitorStatus): Color =
        when (status) {
            MonitorStatus.UP -> up
            MonitorStatus.DOWN -> down
            MonitorStatus.PENDING -> pending
            MonitorStatus.MAINTENANCE -> maintenance
        }

    /** Shifts every hue slightly towards [primary] (Material "harmonize"), keeping the semantics recognisable. */
    fun harmonizedWith(primary: Color): StatusColors =
        copy(
            up = up.harmonize(primary),
            down = down.harmonize(primary),
            pending = pending.harmonize(primary),
            maintenance = maintenance.harmonize(primary),
        )
}

internal val LightStatusColors =
    StatusColors(
        up = Color(0xFF1E9E57),
        down = Color(0xFFD7263D),
        pending = Color(0xFFC77C02),
        maintenance = Color(0xFF2F62E0),
        paused = Color(0xFF78828A),
        unknown = Color(0xFFA2AAB0),
        empty = Color(0x1F78828A),
    )

internal val DarkStatusColors =
    StatusColors(
        up = Color(0xFF5CDD8B),
        down = Color(0xFFFF6B6B),
        pending = Color(0xFFFFB547),
        maintenance = Color(0xFF7FA6FF),
        paused = Color(0xFF9AA4AB),
        unknown = Color(0xFF6E777D),
        empty = Color(0x339AA4AB),
    )

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Status colors of the current [KumaTheme]. */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

private const val MAX_HUE_SHIFT = 15f
private const val HUE_SHIFT_FACTOR = 0.5f
private const val FULL_CIRCLE = 360f
private const val HALF_CIRCLE = 180f
private const val HSL_COMPONENTS = 3

private fun Color.harmonize(primary: Color): Color {
    val source = FloatArray(HSL_COMPONENTS)
    val target = FloatArray(HSL_COMPONENTS)
    ColorUtils.colorToHSL(toArgb(), source)
    ColorUtils.colorToHSL(primary.toArgb(), target)
    val diff = ((target[0] - source[0] + FULL_CIRCLE + HALF_CIRCLE) % FULL_CIRCLE) - HALF_CIRCLE
    val shift = min(abs(diff) * HUE_SHIFT_FACTOR, MAX_HUE_SHIFT) * if (diff < 0) -1 else 1
    source[0] = (source[0] + shift + FULL_CIRCLE) % FULL_CIRCLE
    return Color(ColorUtils.HSLToColor(source)).copy(alpha = alpha)
}
