package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.AlertRecord
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.QuietHours

/** Local alert preferences (which push alerts become notifications). Implemented in :push (DataStore). */
interface AlertPreferencesRepository {
    fun observePreferences(): Flow<AlertPreferences>

    /** The mute of one monitor (also an expired one; check [MonitorMute.isActive]). */
    fun observeMute(monitorId: Long): Flow<MonitorMute?>

    suspend fun setNotifyOnUp(enabled: Boolean)

    suspend fun setNotifyOnCertExpiry(enabled: Boolean)

    suspend fun setQuietHours(quietHours: QuietHours)

    /** Mutes notifications of [monitorId] until [untilMillis] (`null` = until [unmute]). The cache still syncs. */
    suspend fun mute(
        monitorId: Long,
        untilMillis: Long?,
    )

    suspend fun unmute(monitorId: Long)
}

/** The last received push alerts (bounded, newest first). Implemented in :push. */
interface AlertHistoryRepository {
    fun observeAlerts(): Flow<List<AlertRecord>>

    suspend fun clear()
}

/**
 * Called after the monitor cache was refreshed outside the UI (e.g. the sync that follows a push alert),
 * so widgets and tiles can redraw. Contribute implementations with `@Binds @IntoSet`; :push calls all of them.
 */
fun interface MonitorStateListener {
    suspend fun onMonitorStateChanged()
}
