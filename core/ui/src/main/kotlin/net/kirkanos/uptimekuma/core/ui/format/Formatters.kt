package net.kirkanos.uptimekuma.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import net.kirkanos.uptimekuma.core.ui.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val FULL_UPTIME = 0.99995
private const val UPTIME_FRACTION_DIGITS = 2

/** Uptime fraction (0..1) as a localized percentage, e.g. `99.95%` / `99,95 %`; `100%` when (nearly) perfect. */
fun formatUptimePercent(
    fraction: Double,
    locale: Locale,
): String {
    val format = NumberFormat.getPercentInstance(locale)
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = if (fraction >= FULL_UPTIME) 0 else UPTIME_FRACTION_DIGITS
    return format.format(fraction.coerceIn(0.0, 1.0))
}

/** Current locale of the composition (follows per-app language settings). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Response time in milliseconds, e.g. `123 ms`. */
@Composable
fun pingText(pingMs: Double): String = stringResource(R.string.core_ui_ping_ms, pingMs.roundToInt())

/** Localized medium date + short time in the device zone. */
fun formatDateTime(
    timeMillis: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(timeMillis).atZone(zone))

/** Localized medium date in the device zone. */
fun formatDate(
    timeMillis: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(timeMillis).atZone(zone))

/** Localized short time in the device zone, e.g. for chart axes. */
fun formatTime(
    timeMillis: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(timeMillis).atZone(zone))
