package net.kirkanos.uptimekuma.push.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.AlertPreferencesRepository
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.MuteDuration
import net.kirkanos.uptimekuma.core.ui.format.currentLocale
import net.kirkanos.uptimekuma.core.ui.format.formatDateTime
import net.kirkanos.uptimekuma.core.ui.format.formatTime
import net.kirkanos.uptimekuma.core.ui.format.rememberCurrentTimeMillis
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.push.R

/** State and actions of [MonitorAlertToggle] for one monitor. */
@HiltViewModel(assistedFactory = MonitorAlertToggleViewModel.Factory::class)
class MonitorAlertToggleViewModel
    @AssistedInject
    constructor(
        @Assisted private val monitorId: Long,
        private val repository: AlertPreferencesRepository,
    ) : ViewModel() {
        /** The stored mute (possibly expired; the UI checks it against the current time). */
        val mute: StateFlow<MonitorMute?> =
            repository
                .observeMute(monitorId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        fun mute(
            duration: MuteDuration,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            viewModelScope.launch { repository.mute(monitorId, duration.untilMillis(nowMillis)) }
        }

        fun unmute() {
            viewModelScope.launch { repository.unmute(monitorId) }
        }

        @AssistedFactory
        interface Factory {
            fun create(monitorId: Long): MonitorAlertToggleViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/**
 * Per-monitor alert switch for the monitor detail screen: shows whether push alerts of [monitorId] are muted
 * and offers mute durations (1 h, 8 h, 24 h, until unmuted) or unmute. Muting only affects notifications on
 * this device; the cache still syncs and Kuma keeps sending.
 */
@Composable
fun MonitorAlertToggle(
    monitorId: Long,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<MonitorAlertToggleViewModel, MonitorAlertToggleViewModel.Factory>(
            key = "monitor-alert-toggle-$monitorId",
        ) { factory -> factory.create(monitorId) }
    val mute by viewModel.mute.collectAsStateWithLifecycle()
    val now = rememberCurrentTimeMillis()
    MonitorAlertToggleContent(
        mute = mute?.takeIf { it.isActive(now) },
        nowMillis = now,
        onMute = { duration -> viewModel.mute(duration) },
        onUnmute = viewModel::unmute,
        modifier = modifier,
    )
}

/** Stateless variant; [mute] must already be filtered to an active mute. */
@Composable
fun MonitorAlertToggleContent(
    mute: MonitorMute?,
    nowMillis: Long,
    onMute: (MuteDuration) -> Unit,
    onUnmute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val locale = currentLocale()
    val until = mute?.untilMillis
    val supporting =
        when {
            mute == null -> {
                stringResource(R.string.push_monitor_alerts_on)
            }

            until == null -> {
                stringResource(R.string.push_monitor_alerts_muted_indefinitely)
            }

            until - nowMillis < DAY_MILLIS -> {
                stringResource(R.string.push_monitor_alerts_muted_until, formatTime(until, locale))
            }

            else -> {
                stringResource(R.string.push_monitor_alerts_muted_until, formatDateTime(until, locale))
            }
        }
    ListItem(
        modifier = modifier,
        headlineContent = { Text(stringResource(R.string.push_monitor_alerts_title)) },
        supportingContent = { Text(supporting) },
        leadingContent = {
            Icon(
                imageVector = if (mute == null) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                contentDescription = null,
                tint =
                    if (mute == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (mute != null) {
                TextButton(onClick = onUnmute) { Text(stringResource(R.string.push_monitor_alerts_unmute)) }
            } else {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(stringResource(R.string.push_monitor_alerts_mute))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MuteDuration.entries.forEach { duration ->
                            DropdownMenuItem(
                                text = { Text(stringResource(duration.labelRes())) },
                                onClick = {
                                    menuOpen = false
                                    onMute(duration)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

private const val DAY_MILLIS = 24 * 3_600_000L

private fun MuteDuration.labelRes(): Int =
    when (this) {
        MuteDuration.ONE_HOUR -> R.string.push_mute_1h
        MuteDuration.EIGHT_HOURS -> R.string.push_mute_8h
        MuteDuration.ONE_DAY -> R.string.push_mute_24h
        MuteDuration.UNTIL_UNMUTED -> R.string.push_mute_indefinitely
    }

@Preview
@Composable
private fun MonitorAlertToggleOnPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            MonitorAlertToggleContent(mute = null, nowMillis = 0, onMute = {}, onUnmute = {})
        }
    }
}

@Preview(locale = "de")
@Composable
private fun MonitorAlertToggleMutedPreview() {
    KumaTheme(dynamicColor = false) {
        Surface {
            MonitorAlertToggleContent(
                mute = MonitorMute(monitorId = 1, untilMillis = 3_600_000L),
                nowMillis = 0,
                onMute = {},
                onUnmute = {},
            )
        }
    }
}
