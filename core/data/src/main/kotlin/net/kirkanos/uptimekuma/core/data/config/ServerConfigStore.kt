package net.kirkanos.uptimekuma.core.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.kirkanos.uptimekuma.core.data.di.KumaSettings
import net.kirkanos.uptimekuma.core.model.ServerConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret server configuration (base URL, username). The JWT lives in the SecretStore. */
internal interface ServerConfigStore {
    val config: Flow<ServerConfig?>

    suspend fun current(): ServerConfig? = config.first()

    suspend fun save(config: ServerConfig)

    suspend fun clear()
}

@Singleton
internal class DataStoreServerConfigStore
    @Inject
    constructor(
        @KumaSettings private val dataStore: DataStore<Preferences>,
    ) : ServerConfigStore {
        override val config: Flow<ServerConfig?> =
            dataStore.data
                .map { prefs ->
                    val url = prefs[BASE_URL] ?: return@map null
                    ServerConfig(id = prefs[SERVER_ID] ?: 1, baseUrl = url, username = prefs[USERNAME].orEmpty())
                }.distinctUntilChanged()

        override suspend fun save(config: ServerConfig) {
            dataStore.edit {
                it[SERVER_ID] = config.id
                it[BASE_URL] = config.baseUrl
                it[USERNAME] = config.username
            }
        }

        override suspend fun clear() {
            dataStore.edit {
                it.remove(SERVER_ID)
                it.remove(BASE_URL)
                it.remove(USERNAME)
            }
        }

        private companion object {
            val SERVER_ID = longPreferencesKey("server.id")
            val BASE_URL = stringPreferencesKey("server.baseUrl")
            val USERNAME = stringPreferencesKey("server.username")
        }
    }
