package net.kirkanos.uptimekuma.push.firebase

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import net.kirkanos.uptimekuma.core.model.FirebaseConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret settings of the Firebase transport. The relay token lives in the encrypted secret store. */
data class FirebaseSettings(
    /** Normalized relay base URL (see [RelayUrl.normalize]). */
    val relayUrl: String,
    val firebase: FirebaseConfig,
)

/**
 * Blocking store for [FirebaseSettings]. SharedPreferences (not DataStore) on purpose: `Application.onCreate`
 * must read the Firebase values synchronously to initialize Firebase before the messaging service is bound.
 * The values are kept after a teardown so that the form is prefilled when the user comes back.
 */
interface FirebaseSettingsStore {
    fun load(): FirebaseSettings?

    fun save(settings: FirebaseSettings)
}

@Singleton
internal class SharedPrefsFirebaseSettingsStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : FirebaseSettingsStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        @Synchronized
        override fun load(): FirebaseSettings? {
            val relayUrl = prefs.getString(KEY_RELAY_URL, null) ?: return null
            return FirebaseSettings(
                relayUrl = relayUrl,
                firebase =
                    FirebaseConfig(
                        projectId = prefs.getString(KEY_PROJECT_ID, null).orEmpty(),
                        applicationId = prefs.getString(KEY_APP_ID, null).orEmpty(),
                        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
                        gcmSenderId = prefs.getString(KEY_SENDER_ID, null).orEmpty(),
                    ),
            )
        }

        @Synchronized
        override fun save(settings: FirebaseSettings) {
            // commit, not apply: a restart right after saving (project change) must not lose the values.
            prefs.edit(commit = true) {
                putString(KEY_RELAY_URL, settings.relayUrl)
                putString(KEY_PROJECT_ID, settings.firebase.projectId)
                putString(KEY_APP_ID, settings.firebase.applicationId)
                putString(KEY_API_KEY, settings.firebase.apiKey)
                putString(KEY_SENDER_ID, settings.firebase.gcmSenderId)
            }
        }

        private companion object {
            const val PREFS_NAME = "push_firebase"
            const val KEY_RELAY_URL = "relay_url"
            const val KEY_PROJECT_ID = "project_id"
            const val KEY_APP_ID = "application_id"
            const val KEY_API_KEY = "api_key"
            const val KEY_SENDER_ID = "gcm_sender_id"
        }
    }
