package net.kirkanos.uptimekuma.push.alerts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import net.kirkanos.uptimekuma.core.domain.AlertPreferencesRepository
import net.kirkanos.uptimekuma.core.model.AlertPreferences
import net.kirkanos.uptimekuma.core.model.MonitorMute
import net.kirkanos.uptimekuma.core.model.QuietHours
import net.kirkanos.uptimekuma.push.di.PushScope
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.alertPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "alert_preferences",
)

/**
 * [AlertPreferencesRepository] backed by a Preferences DataStore (`alert_preferences`).
 *
 * Mutes are stored as `mute_<monitorId>` = end time in epoch millis ([INDEFINITE] = until unmuted); expired
 * mutes are pruned on every write. [current] gives the notifier a synchronous snapshot: the values are kept
 * hot in the push scope after the first read.
 */
@Singleton
class DataStoreAlertPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val clock: Clock,
) : AlertPreferencesRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @PushScope scope: CoroutineScope,
    ) : this(context.alertPreferencesDataStore, scope, Clock.systemDefaultZone())

    private val preferences: Flow<AlertPreferences> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it.toAlertPreferences() }
            .distinctUntilChanged()

    private val cached: StateFlow<AlertPreferences?> = preferences.stateIn(scope, SharingStarted.Eagerly, null)

    override fun observePreferences(): Flow<AlertPreferences> = preferences

    override fun observeMute(monitorId: Long): Flow<MonitorMute?> =
        preferences.map { it.mutes[monitorId] }.distinctUntilChanged()

    /** Latest preferences without suspending; blocks briefly only before the first DataStore read finished. */
    fun current(): AlertPreferences = cached.value ?: runBlocking { preferences.first() }

    override suspend fun setNotifyOnUp(enabled: Boolean) {
        edit { it[KEY_NOTIFY_UP] = enabled }
    }

    override suspend fun setNotifyOnCertExpiry(enabled: Boolean) {
        edit { it[KEY_NOTIFY_CERT] = enabled }
    }

    override suspend fun setQuietHours(quietHours: QuietHours) {
        edit {
            it[KEY_QUIET_ENABLED] = quietHours.enabled
            it[KEY_QUIET_START] = quietHours.startMinuteOfDay.coerceIn(0, QuietHours.MINUTES_PER_DAY - 1)
            it[KEY_QUIET_END] = quietHours.endMinuteOfDay.coerceIn(0, QuietHours.MINUTES_PER_DAY - 1)
        }
    }

    override suspend fun mute(
        monitorId: Long,
        untilMillis: Long?,
    ) {
        edit { it[muteKey(monitorId)] = untilMillis ?: INDEFINITE }
    }

    override suspend fun unmute(monitorId: Long) {
        edit { it.remove(muteKey(monitorId)) }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { prefs ->
            pruneExpiredMutes(prefs)
            block(prefs)
        }
    }

    private fun pruneExpiredMutes(prefs: MutablePreferences) {
        val now = clock.millis()
        prefs
            .asMap()
            .filter { (key, value) ->
                key.name.startsWith(MUTE_PREFIX) && value is Long && value != INDEFINITE && value <= now
            }.keys
            .forEach { prefs.remove(it) }
    }

    private fun Preferences.toAlertPreferences(): AlertPreferences {
        val mutes =
            asMap()
                .mapNotNull { (key, value) ->
                    val id = key.name.removePrefix(MUTE_PREFIX).toLongOrNull()
                    if (!key.name.startsWith(MUTE_PREFIX) || id == null || value !is Long) {
                        null
                    } else {
                        id to MonitorMute(monitorId = id, untilMillis = value.takeIf { it != INDEFINITE })
                    }
                }.toMap()
        return AlertPreferences(
            notifyOnUp = this[KEY_NOTIFY_UP] ?: true,
            notifyOnCertExpiry = this[KEY_NOTIFY_CERT] ?: true,
            quietHours =
                QuietHours(
                    enabled = this[KEY_QUIET_ENABLED] ?: false,
                    startMinuteOfDay = this[KEY_QUIET_START] ?: QuietHours.DEFAULT_START,
                    endMinuteOfDay = this[KEY_QUIET_END] ?: QuietHours.DEFAULT_END,
                ),
            mutes = mutes,
        )
    }

    internal companion object {
        const val MUTE_PREFIX = "mute_"
        const val INDEFINITE = -1L
        val KEY_NOTIFY_UP = booleanPreferencesKey("notify_up")
        val KEY_NOTIFY_CERT = booleanPreferencesKey("notify_cert_expiry")
        val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val KEY_QUIET_START = intPreferencesKey("quiet_start_minute")
        val KEY_QUIET_END = intPreferencesKey("quiet_end_minute")

        fun muteKey(monitorId: Long): Preferences.Key<Long> = longPreferencesKey("$MUTE_PREFIX$monitorId")
    }
}
