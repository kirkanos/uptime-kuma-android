package net.kirkanos.uptimekuma.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import net.kirkanos.uptimekuma.core.ui.R

/** Coarse distance between two instants, the input for a localized "3 min ago" text. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime

    data class Minutes(
        val count: Int,
    ) : RelativeTime

    data class Hours(
        val count: Int,
    ) : RelativeTime

    data class Days(
        val count: Int,
    ) : RelativeTime

    companion object {
        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        private const val JUST_NOW_THRESHOLD = 45 * SECOND

        /** Clock skew (beats slightly in the future) is treated as "just now". */
        fun between(
            timeMillis: Long,
            nowMillis: Long,
        ): RelativeTime {
            val diff = nowMillis - timeMillis
            return when {
                diff < JUST_NOW_THRESHOLD -> JustNow
                diff < HOUR -> Minutes((diff / MINUTE).toInt().coerceAtLeast(1))
                diff < DAY -> Hours((diff / HOUR).toInt())
                else -> Days((diff / DAY).toInt())
            }
        }
    }
}

/** Localized relative time, e.g. "3 min ago" / "vor 3 Min.". */
@Composable
fun relativeTimeText(
    timeMillis: Long,
    nowMillis: Long,
): String =
    when (val relative = RelativeTime.between(timeMillis, nowMillis)) {
        RelativeTime.JustNow -> {
            stringResource(R.string.core_ui_time_just_now)
        }

        is RelativeTime.Minutes -> {
            pluralStringResource(R.plurals.core_ui_time_minutes_ago, relative.count, relative.count)
        }

        is RelativeTime.Hours -> {
            pluralStringResource(R.plurals.core_ui_time_hours_ago, relative.count, relative.count)
        }

        is RelativeTime.Days -> {
            pluralStringResource(R.plurals.core_ui_time_days_ago, relative.count, relative.count)
        }
    }

/**
 * Current wall-clock time that refreshes every [periodMillis]. Use once per screen and pass the value
 * down, so relative times update without a ticker per row.
 */
@Composable
fun rememberCurrentTimeMillis(periodMillis: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}
