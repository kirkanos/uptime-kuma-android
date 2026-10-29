package net.kirkanos.uptimekuma.push.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.core.model.PushRegistration
import net.kirkanos.uptimekuma.core.model.PushTransport
import javax.inject.Inject
import javax.inject.Singleton

/** Persists this device's [PushRegistration]. */
interface PushRegistrationStore {
    fun observe(): Flow<PushRegistration?>

    suspend fun get(): PushRegistration? = observe().first()

    /** Atomic read-modify-write; returning `null` clears the registration. */
    suspend fun update(transform: (PushRegistration?) -> PushRegistration?)

    suspend fun clear() = update { null }
}

private val Context.pushDataStore: DataStore<Preferences> by preferencesDataStore(name = "push_registration")

@Singleton
internal class DataStorePushRegistrationStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : PushRegistrationStore {
        private val dataStore = context.pushDataStore

        override fun observe(): Flow<PushRegistration?> = dataStore.data.map { it.toRegistration() }

        override suspend fun update(transform: (PushRegistration?) -> PushRegistration?) {
            dataStore.edit { prefs ->
                val next = transform(prefs.toRegistration())
                prefs.clear()
                if (next != null) {
                    prefs[KEY_ENDPOINT] = next.endpoint
                    next.kumaNotificationId?.let { prefs[KEY_NOTIFICATION_ID] = it }
                    next.distributor?.let { prefs[KEY_DISTRIBUTOR] = it }
                    prefs[KEY_TRANSPORT] = next.transport.name
                }
            }
        }

        private fun Preferences.toRegistration(): PushRegistration? {
            val endpoint = this[KEY_ENDPOINT] ?: return null
            return PushRegistration(
                endpoint = endpoint,
                kumaNotificationId = this[KEY_NOTIFICATION_ID],
                distributor = this[KEY_DISTRIBUTOR],
                // Absent in registrations stored before the Firebase transport existed: those are ntfy ones.
                transport =
                    PushTransport.entries.firstOrNull { it.name == this[KEY_TRANSPORT] } ?: PushTransport.NTFY,
            )
        }

        private companion object {
            val KEY_ENDPOINT = stringPreferencesKey("endpoint")
            val KEY_NOTIFICATION_ID = longPreferencesKey("kuma_notification_id")
            val KEY_DISTRIBUTOR = stringPreferencesKey("distributor")
            val KEY_TRANSPORT = stringPreferencesKey("transport")
        }
    }
