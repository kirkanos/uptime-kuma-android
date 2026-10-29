package net.kirkanos.uptimekuma.feature.settings.main

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.settings.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/** Localized short time of a minute-of-day value, e.g. `22:00` / `10:00 PM`. */
internal fun formatMinuteOfDay(
    minuteOfDay: Int,
    locale: Locale,
): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(LocalTime.of(minuteOfDay / MINUTES_PER_HOUR % HOURS_PER_DAY, minuteOfDay % MINUTES_PER_HOUR))

/** Material 3 time picker in a dialog; returns the picked time as minute of day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val context = LocalContext.current
    val pickerState =
        rememberTimePickerState(
            initialHour = initialMinuteOfDay / MINUTES_PER_HOUR,
            initialMinute = initialMinuteOfDay % MINUTES_PER_HOUR,
            is24Hour = DateFormat.is24HourFormat(context),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * MINUTES_PER_HOUR + pickerState.minute) }) {
                Text(stringResource(R.string.settings_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Preview
@Composable
private fun TimePickerDialogPreview() {
    KumaTheme(dynamicColor = false) {
        TimePickerDialog(title = "Start", initialMinuteOfDay = 22 * 60, onDismiss = {}, onConfirm = {})
    }
}
