package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.model.TwoFactorSetup

/*
 * Server administration (the Kuma web UI's Settings area). Implemented in :core:data on top of the socket.
 * All suspend functions throw net.kirkanos.uptimekuma.core.model.KumaException on failure; server rejections carry
 * Kuma's message (often an i18n key such as `passwordTooWeak`) in KumaError.message.
 */

/** `getSettings`/`setSettings` plus the database maintenance events. */
interface ServerSettingsRepository {
    /** Post-login `info` of the connected server; `null` while unknown. */
    fun observeServerDetails(): Flow<ServerDetails?>

    /** `getSettings` with the web UI's defaults applied. */
    suspend fun load(): ServerSettings

    /**
     * Saves like the web UI: fetches the stored settings again, overlays [settings] and sends the whole object
     * with `setSettings`. [ServerSettings.disableAuth] is never changed here, see [setAuthDisabled].
     */
    suspend fun save(settings: ServerSettings)

    /** Toggles `disableAuth`; [currentPassword] is required (and verified) to disable authentication. */
    suspend fun setAuthDisabled(
        disabled: Boolean,
        currentPassword: String?,
    )

    /** `testChrome`: returns the found Chromium version. */
    suspend fun testChrome(executable: String): String?

    /** `getDatabaseSize` in bytes. */
    suspend fun databaseSize(): Long

    /** `shrinkDatabase` (SQLite `VACUUM`). */
    suspend fun shrinkDatabase()

    /** `clearStatistics`: deletes all heartbeats and statistics of every monitor. */
    suspend fun clearStatistics()
}

/** The logged-in account: password and two-factor authentication. */
interface AccountRepository {
    /**
     * `changePassword`. Kuma invalidates every old token and returns a new one, which is stored so the app stays
     * logged in (other sessions of the user are logged out by the server).
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    )

    /** `twoFAStatus`: true when 2FA is active. */
    suspend fun twoFactorEnabled(): Boolean

    /** `prepare2FA`: generates and stores a new (not yet active) secret. */
    suspend fun prepareTwoFactor(currentPassword: String): TwoFactorSetup

    /** `verifyToken`: true when [token] is a valid TOTP code for the prepared/active secret. */
    suspend fun verifyTwoFactorToken(
        token: String,
        currentPassword: String,
    ): Boolean

    /** `save2FA`: activates the prepared secret. */
    suspend fun enableTwoFactor(currentPassword: String)

    /** `disable2FA`. */
    suspend fun disableTwoFactor(currentPassword: String)
}

/** API keys for the `/metrics` endpoint (`apiKeyList` snapshot). */
interface ApiKeyRepository {
    /** `null` until the server pushed `apiKeyList`. */
    fun observeApiKeys(): Flow<List<ApiKey>?>

    /** `getAPIKeyList` (re-pushes the list; also makes sure a session exists). */
    suspend fun refresh()

    suspend fun create(draft: ApiKeyDraft): CreatedApiKey

    suspend fun setActive(
        id: Long,
        active: Boolean,
    )

    suspend fun delete(id: Long)
}

/** Proxies (`proxyList` snapshot). */
interface ProxyRepository {
    fun observeProxies(): Flow<List<Proxy>?>

    /** `addProxy`; returns the id. */
    suspend fun save(draft: ProxyDraft): Long

    suspend fun delete(id: Long)
}

/** Docker hosts (`dockerHostList` snapshot). */
interface DockerHostRepository {
    fun observeDockerHosts(): Flow<List<DockerHost>?>

    suspend fun save(draft: DockerHostDraft): Long

    /** `testDockerHost`; returns the server's message (e.g. container count). */
    suspend fun test(draft: DockerHostDraft): String?

    suspend fun delete(id: Long)
}

/** Remote browsers (`remoteBrowserList` snapshot). */
interface RemoteBrowserRepository {
    fun observeRemoteBrowsers(): Flow<List<RemoteBrowser>?>

    suspend fun save(draft: RemoteBrowserDraft): Long

    suspend fun test(draft: RemoteBrowserDraft): String?

    suspend fun delete(id: Long)
}

/** The server's built-in Cloudflare tunnel (`cloudflared_*` events, most of them without ack). */
interface CloudflaredRepository {
    fun observeState(): Flow<CloudflaredState>

    /** Subscribes to tunnel updates (`cloudflared_join`); call while the screen is visible. */
    suspend fun join()

    suspend fun leave()

    /** Stores [token] and starts the tunnel. */
    suspend fun start(token: String)

    /** Stops the tunnel; [currentPassword] is required unless authentication is disabled. */
    suspend fun stop(currentPassword: String?)

    suspend fun removeToken()
}
