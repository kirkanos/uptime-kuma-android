package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton

/** [ServerSettingsRepository] over `getSettings`/`setSettings` (always the whole object, like the web UI). */
@Singleton
internal class KumaServerSettingsRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : ServerSettingsRepository {
        override fun observeServerDetails(): Flow<ServerDetails?> =
            backend
                .snapshot(INFO)
                .map(AdminJson::decodeDetails)
                .distinctUntilChanged()

        override suspend fun load(): ServerSettings = AdminJson.decodeSettings(storedSettings())

        override suspend fun save(settings: ServerSettings) {
            val stored = storedSettings()
            val currentDisableAuth = AdminJson.decodeSettings(stored).disableAuth
            backend.call("setSettings", AdminJson.settingsPayload(stored, settings, currentDisableAuth), null)
        }

        override suspend fun setAuthDisabled(
            disabled: Boolean,
            currentPassword: String?,
        ) {
            val stored = storedSettings()
            val settings = AdminJson.decodeSettings(stored)
            backend.call("setSettings", AdminJson.settingsPayload(stored, settings, disabled), currentPassword)
        }

        override suspend fun testChrome(executable: String): String? {
            val ack = backend.call("testChrome", executable.trim())
            // {msg: {key: "foundChromiumVersion", values: [version]}}
            val msg = ack["msg"]
            return ((msg as? JsonObject)?.get("values") as? JsonArray)?.firstOrNull().asStringOrNull()
                ?: msg.asStringOrNull()
        }

        override suspend fun databaseSize(): Long =
            backend.call("getDatabaseSize")["size"].asLenientLong()
                ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "getDatabaseSize without size"))

        override suspend fun shrinkDatabase() {
            backend.call("shrinkDatabase", timeoutMillis = SLOW_TIMEOUT_MILLIS)
        }

        override suspend fun clearStatistics() {
            backend.call("clearStatistics", timeoutMillis = SLOW_TIMEOUT_MILLIS)
        }

        private suspend fun storedSettings(): JsonObject = AdminJson.settingsData(backend.call("getSettings"))

        private companion object {
            const val INFO = "info"

            /** `VACUUM` and deleting all statistics can take a while on big databases. */
            const val SLOW_TIMEOUT_MILLIS = 120_000L
        }
    }
