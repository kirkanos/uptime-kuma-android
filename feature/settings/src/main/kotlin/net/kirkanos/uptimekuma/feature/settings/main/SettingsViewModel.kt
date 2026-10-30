package net.kirkanos.uptimekuma.feature.settings.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.kirkanos.uptimekuma.core.domain.AlertPreferencesRepository
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.core.model.RepeatInterval
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.model.ServerInfo
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val serverConfig: ServerConfig? = null,
    val serverInfo: ServerInfo? = null,
    val alertPreferences: AlertPreferences = AlertPreferences(),
)

/** App settings: server/account, alert preferences, logout. After logout the app returns to onboarding. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        serverInfoRepository: ServerInfoRepository,
        private val alertPreferences: AlertPreferencesRepository,
    ) : ViewModel() {
        val state: StateFlow<SettingsUiState> =
            combine(
                sessionRepository.observeServerConfig(),
                serverInfoRepository.observeServerInfo(),
                alertPreferences.observePreferences(),
            ) { config, info, prefs ->
                SettingsUiState(loading = false, serverConfig = config, serverInfo = info, alertPreferences = prefs)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        fun setNotifyOnUp(enabled: Boolean) {
            viewModelScope.launch { alertPreferences.setNotifyOnUp(enabled) }
        }

        fun setNotifyOnCertExpiry(enabled: Boolean) {
            viewModelScope.launch { alertPreferences.setNotifyOnCertExpiry(enabled) }
        }

        fun setQuietHoursEnabled(enabled: Boolean) {
            updateQuietHours { it.copy(enabled = enabled) }
        }

        fun setQuietHoursStart(minuteOfDay: Int) {
            updateQuietHours { it.copy(startMinuteOfDay = minuteOfDay) }
        }

        fun setQuietHoursEnd(minuteOfDay: Int) {
            updateQuietHours { it.copy(endMinuteOfDay = minuteOfDay) }
        }

        fun setRepeatDown(interval: RepeatInterval) {
            viewModelScope.launch { alertPreferences.setRepeatDown(interval) }
        }

        fun logout() {
            viewModelScope.launch { sessionRepository.logout() }
        }

        private fun updateQuietHours(transform: (QuietHours) -> QuietHours) {
            viewModelScope.launch {
                val current = alertPreferences.observePreferences().first().quietHours
                alertPreferences.setQuietHours(transform(current))
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
