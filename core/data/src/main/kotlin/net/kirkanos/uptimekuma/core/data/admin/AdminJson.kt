package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.EntryPage
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ProxyProtocol
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Decoders for the administration payloads (`getSettings`, `info`, `apiKeyList`, `proxyList`, `dockerHostList`,
 * `remoteBrowserList`) and builders for what the web UI sends (`docs/kuma-api/socket-events.md` 3.7, 3.10).
 */
internal object AdminJson {
    /** What `APIKeyDialog.vue` submits for `expires` (server stores it verbatim). */
    private val API_KEY_EXPIRES: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // ---- settings ---------------------------------------------------------------------------------

    /** `getSettings` ack `{ok, data:{...}}` -> the stored keys (empty when the server has none). */
    fun settingsData(ack: JsonObject): JsonObject = ack["data"] as? JsonObject ?: JsonObject(emptyMap())

    /** Typed view on the stored settings with the web UI's defaults (`Settings.vue` `loadSettings`). */
    fun decodeSettings(data: JsonObject): ServerSettings {
        val defaults = ServerSettings()
        return ServerSettings(
            serverTimezone = data["serverTimezone"].asStringOrNull().orEmpty(),
            primaryBaseUrl = data["primaryBaseURL"].asStringOrNull().orEmpty(),
            searchEngineIndex = data["searchEngineIndex"].asLenientBoolean() ?: defaults.searchEngineIndex,
            entryPage = data["entryPage"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: EntryPage.DASHBOARD,
            steamApiKey = data["steamAPIKey"].asStringOrNull().orEmpty(),
            globalpingApiToken = data["globalpingApiToken"].asStringOrNull().orEmpty(),
            nscd = data["nscd"].asLenientBoolean() ?: defaults.nscd,
            chromeExecutable = data["chromeExecutable"].asStringOrNull().orEmpty(),
            trustProxy = data["trustProxy"].asLenientBoolean() ?: defaults.trustProxy,
            keepDataPeriodDays =
                data["keepDataPeriodDays"].asLenientLong()?.toInt() ?: defaults.keepDataPeriodDays,
            tlsExpiryNotifyDays = days(data["tlsExpiryNotifyDays"]) ?: defaults.tlsExpiryNotifyDays,
            domainExpiryNotifyDays = days(data["domainExpiryNotifyDays"]) ?: defaults.domainExpiryNotifyDays,
            checkUpdate = data["checkUpdate"].asLenientBoolean() ?: defaults.checkUpdate,
            checkBeta = data["checkBeta"].asLenientBoolean() ?: defaults.checkBeta,
            disableAuth = data["disableAuth"].asLenientBoolean() ?: defaults.disableAuth,
        )
    }

    /**
     * The `setSettings` payload: every stored key of [stored] (also those the app does not know) overlaid with
     * [settings]. `disableAuth` is taken from [disableAuth] so a normal save can never switch it.
     */
    fun settingsPayload(
        stored: JsonObject,
        settings: ServerSettings,
        disableAuth: Boolean,
    ): JsonObject =
        JsonObject(
            stored +
                buildJsonObject {
                    // getSettings always reports a zone; never replace it with an empty one.
                    settings.serverTimezone.takeIf { it.isNotBlank() }?.let { put("serverTimezone", it) }
                    putText(stored, "primaryBaseURL", settings.primaryBaseUrl)
                    put("searchEngineIndex", settings.searchEngineIndex)
                    put("entryPage", settings.entryPage)
                    putText(stored, "steamAPIKey", settings.steamApiKey)
                    putText(stored, "globalpingApiToken", settings.globalpingApiToken)
                    put("nscd", settings.nscd)
                    putText(stored, "chromeExecutable", settings.chromeExecutable)
                    put("trustProxy", settings.trustProxy)
                    put("keepDataPeriodDays", settings.keepDataPeriodDays)
                    putJsonArray(
                        "tlsExpiryNotifyDays",
                    ) { settings.tlsExpiryNotifyDays.forEach { add(JsonPrimitive(it)) } }
                    settings.domainExpiryNotifyDays?.let { list ->
                        putJsonArray("domainExpiryNotifyDays") { list.forEach { add(JsonPrimitive(it)) } }
                    }
                    put("checkUpdate", settings.checkUpdate)
                    put("checkBeta", settings.checkBeta)
                    put("disableAuth", disableAuth)
                },
        )

    /** Text fields: an empty value is only written over a stored text, so unset keys stay unset. */
    private fun JsonObjectBuilder.putText(
        stored: JsonObject,
        key: String,
        value: String,
    ) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() || stored[key].asStringOrNull() != null) put(key, trimmed)
    }

    private fun days(element: JsonElement?): List<Int>? =
        (element as? JsonArray)
            ?.mapNotNull { it.asLenientLong()?.toInt() }
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()

    // ---- info -------------------------------------------------------------------------------------

    /** `info` event args `[{...}]`; `null` before the first event. */
    fun decodeDetails(args: List<JsonElement>?): ServerDetails? {
        val obj = args?.firstOrNull() as? JsonObject ?: return null
        val runtime = obj["runtime"] as? JsonObject
        return ServerDetails(
            version = obj["version"].asStringOrNull(),
            latestVersion = obj["latestVersion"].asStringOrNull(),
            primaryBaseUrl = obj["primaryBaseURL"].asStringOrNull(),
            serverTimezone = obj["serverTimezone"].asStringOrNull(),
            serverTimezoneOffset = obj["serverTimezoneOffset"].asStringOrNull(),
            isContainer = obj["isContainer"].asLenientBoolean(),
            dbType = obj["dbType"].asStringOrNull(),
            runtimePlatform = runtime?.get("platform").asStringOrNull(),
            runtimeArch = runtime?.get("arch").asStringOrNull(),
        )
    }

    // ---- lists ------------------------------------------------------------------------------------

    private fun rows(args: List<JsonElement>?): List<JsonObject>? =
        (args?.firstOrNull() as? JsonArray)?.mapNotNull { it as? JsonObject }

    /** `apiKeyList`: `[{id, name, userID, createdDate, active, expires, status}]`, newest first. */
    fun decodeApiKeys(args: List<JsonElement>?): List<ApiKey>? =
        rows(args)
            ?.mapNotNull { row ->
                val id = row["id"].asLenientLong() ?: return@mapNotNull null
                ApiKey(
                    id = id,
                    name = row["name"].asStringOrNull().orEmpty(),
                    active = row["active"].asLenientBoolean() ?: false,
                    status = ApiKeyStatus.fromWire(row["status"].asStringOrNull()),
                    createdDate = row["createdDate"].asStringOrNull()?.let(::parseDateTime),
                    expires = row["expires"].asStringOrNull()?.let(::parseDateTime),
                )
            }?.sortedByDescending { it.id }

    /** `proxyList`: bean rows with 0/1 booleans. */
    fun decodeProxies(args: List<JsonElement>?): List<Proxy>? =
        rows(args)
            ?.mapNotNull { row ->
                val id = row["id"].asLenientLong() ?: return@mapNotNull null
                Proxy(
                    id = id,
                    protocol = ProxyProtocol.fromWire(row["protocol"].asStringOrNull()),
                    host = row["host"].asStringOrNull().orEmpty(),
                    port = row["port"].asLenientLong()?.toInt(),
                    auth = row["auth"].asLenientBoolean() ?: false,
                    username = row["username"].asStringOrNull().orEmpty(),
                    password = row["password"].asStringOrNull().orEmpty(),
                    active = row["active"].asLenientBoolean() ?: true,
                    isDefault = row["default"].asLenientBoolean() ?: false,
                )
            }?.sortedBy { it.id }

    fun decodeDockerHosts(args: List<JsonElement>?): List<DockerHost>? =
        rows(args)
            ?.mapNotNull { row ->
                val id = row["id"].asLenientLong() ?: return@mapNotNull null
                DockerHost(
                    id = id,
                    name = row["name"].asStringOrNull().orEmpty(),
                    type = DockerHostType.fromWire(row["dockerType"].asStringOrNull()),
                    daemon = row["dockerDaemon"].asStringOrNull().orEmpty(),
                )
            }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DockerHost::name).thenBy { it.id })

    fun decodeRemoteBrowsers(args: List<JsonElement>?): List<RemoteBrowser>? =
        rows(args)
            ?.mapNotNull { row ->
                val id = row["id"].asLenientLong() ?: return@mapNotNull null
                RemoteBrowser(id, row["name"].asStringOrNull().orEmpty(), row["url"].asStringOrNull().orEmpty())
            }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RemoteBrowser::name).thenBy { it.id })

    /** `YYYY-MM-DD HH:mm[:ss[.SSS]]`, with `T` or space, optionally with a `Z`. */
    fun parseDateTime(text: String): LocalDateTime? {
        val trimmed = text.trim().removeSuffix("Z").replace(' ', 'T')
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    // ---- payloads ---------------------------------------------------------------------------------

    fun apiKeyPayload(draft: ApiKeyDraft): JsonObject =
        buildJsonObject {
            put("name", draft.name.trim())
            put("expires", draft.expires?.let { JsonPrimitive(API_KEY_EXPIRES.format(it)) } ?: JsonNull)
            put("active", draft.active)
        }

    /** `ProxyDialog.vue` object; credentials are only sent with `auth`. */
    fun proxyPayload(draft: ProxyDraft): JsonObject =
        buildJsonObject {
            put("protocol", draft.protocol.wire)
            put("host", draft.host.trim())
            put("port", draft.port?.let(::JsonPrimitive) ?: JsonNull)
            put("auth", draft.auth)
            put("username", if (draft.auth) JsonPrimitive(draft.username) else JsonNull)
            put("password", if (draft.auth) JsonPrimitive(draft.password) else JsonNull)
            put("active", draft.active)
            put("default", draft.isDefault)
            put("applyExisting", draft.applyExisting)
        }

    fun dockerHostPayload(draft: DockerHostDraft): JsonObject =
        buildJsonObject {
            put("name", draft.name.trim())
            put("dockerType", draft.type.wire)
            put("dockerDaemon", draft.daemon.trim())
        }

    fun remoteBrowserPayload(draft: RemoteBrowserDraft): JsonObject =
        buildJsonObject {
            put("name", draft.name.trim())
            put("url", draft.url.trim())
        }
}
