package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.core.domain.AlertHistoryRepository
import net.kirkanos.uptimekuma.core.domain.AlertPreferencesRepository
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.core.model.RepeatInterval

/** In-memory [AlertPreferencesRepository]; [preferences] can be set directly. */
class FakeAlertPreferencesRepository(
    initial: AlertPreferences = AlertPreferences(),
) : AlertPreferencesRepository {
    val preferences = MutableStateFlow(initial)

    override fun observePreferences(): Flow<AlertPreferences> = preferences

    override fun observeMute(monitorId: Long): Flow<MonitorMute?> =
        preferences.map { it.mutes[monitorId] }.distinctUntilChanged()

    override suspend fun setNotifyOnUp(enabled: Boolean) {
        preferences.value = preferences.value.copy(notifyOnUp = enabled)
    }

    override suspend fun setNotifyOnCertExpiry(enabled: Boolean) {
        preferences.value = preferences.value.copy(notifyOnCertExpiry = enabled)
    }

    override suspend fun setQuietHours(quietHours: QuietHours) {
        preferences.value = preferences.value.copy(quietHours = quietHours)
    }

    override suspend fun setRepeatDown(interval: RepeatInterval) {
        preferences.value = preferences.value.copy(repeatDown = interval)
    }

    override suspend fun mute(
        monitorId: Long,
        untilMillis: Long?,
    ) {
        val mutes = preferences.value.mutes + (monitorId to MonitorMute(monitorId, untilMillis))
        preferences.value = preferences.value.copy(mutes = mutes)
    }

    override suspend fun unmute(monitorId: Long) {
        preferences.value = preferences.value.copy(mutes = preferences.value.mutes - monitorId)
    }
}

/** In-memory [AlertHistoryRepository]; [alerts] is newest first. */
class FakeAlertHistoryRepository(
    initial: List<AlertRecord> = emptyList(),
) : AlertHistoryRepository {
    val alerts = MutableStateFlow(initial)
    var clearCount = 0
        private set

    override fun observeAlerts(): Flow<List<AlertRecord>> = alerts

    override suspend fun clear() {
        clearCount++
        alerts.value = emptyList()
    }
}
