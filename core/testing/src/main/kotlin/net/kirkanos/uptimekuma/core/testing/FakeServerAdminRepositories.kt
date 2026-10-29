package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.kirkanos.uptimekuma.core.domain.AccountRepository
import net.kirkanos.uptimekuma.core.domain.ApiKeyRepository
import net.kirkanos.uptimekuma.core.domain.CloudflaredRepository
import net.kirkanos.uptimekuma.core.domain.DockerHostRepository
import net.kirkanos.uptimekuma.core.domain.ProxyRepository
import net.kirkanos.uptimekuma.core.domain.RemoteBrowserRepository
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.model.ServerDetails
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.model.TwoFactorSetup

/** Base for the admin fakes: records calls and fails the next call when [failWith] is set. */
abstract class RecordingFake {
    val calls = mutableListOf<String>()

    /** When set, every call throws `KumaException(failWith)`. */
    var failWith: KumaError? = null

    protected fun record(call: String) {
        calls += call
        failWith?.let { throw KumaException(it) }
    }
}

/** In-memory [ServerSettingsRepository]. */
class FakeServerSettingsRepository(
    var settings: ServerSettings = ServerSettings(),
    details: ServerDetails? = ServerDetails(version = "2.5.8", dbType = "sqlite"),
) : RecordingFake(),
    ServerSettingsRepository {
    val details = MutableStateFlow(details)
    var databaseSize = 1_048_576L
    var chromeVersion: String? = "120.0"
    var authDisabledPassword: String? = null

    override fun observeServerDetails(): Flow<ServerDetails?> = details

    override suspend fun load(): ServerSettings {
        record("load")
        return settings
    }

    override suspend fun save(settings: ServerSettings) {
        record("save")
        this.settings = settings.copy(disableAuth = this.settings.disableAuth)
    }

    override suspend fun setAuthDisabled(
        disabled: Boolean,
        currentPassword: String?,
    ) {
        record("setAuthDisabled:$disabled")
        authDisabledPassword = currentPassword
        settings = settings.copy(disableAuth = disabled)
    }

    override suspend fun testChrome(executable: String): String? {
        record("testChrome:$executable")
        return chromeVersion
    }

    override suspend fun databaseSize(): Long {
        record("databaseSize")
        return databaseSize
    }

    override suspend fun shrinkDatabase() {
        record("shrinkDatabase")
        databaseSize /= 2
    }

    override suspend fun clearStatistics() {
        record("clearStatistics")
    }
}

/** In-memory [AccountRepository]; [password] is the current password, [validCode] the accepted TOTP. */
class FakeAccountRepository(
    var password: String = "old-Passw0rd!",
    var twoFactor: Boolean = false,
    var validCode: String = "123456",
) : RecordingFake(),
    AccountRepository {
    var prepared = false

    private fun checkPassword(currentPassword: String) {
        if (currentPassword != password) {
            throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Incorrect current password"))
        }
    }

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        record("changePassword")
        checkPassword(currentPassword)
        password = newPassword
    }

    override suspend fun twoFactorEnabled(): Boolean {
        record("twoFactorEnabled")
        return twoFactor
    }

    override suspend fun prepareTwoFactor(currentPassword: String): TwoFactorSetup {
        record("prepareTwoFactor")
        checkPassword(currentPassword)
        prepared = true
        return TwoFactorSetup("otpauth://totp/Uptime%20Kuma:admin?secret=JBSWY3DPEHPK3PXP")
    }

    override suspend fun verifyTwoFactorToken(
        token: String,
        currentPassword: String,
    ): Boolean {
        record("verifyTwoFactorToken:$token")
        checkPassword(currentPassword)
        return token == validCode
    }

    override suspend fun enableTwoFactor(currentPassword: String) {
        record("enableTwoFactor")
        checkPassword(currentPassword)
        twoFactor = true
    }

    override suspend fun disableTwoFactor(currentPassword: String) {
        record("disableTwoFactor")
        checkPassword(currentPassword)
        twoFactor = false
    }
}

/** In-memory [ApiKeyRepository]. */
class FakeApiKeyRepository(
    initial: List<ApiKey>? = emptyList(),
) : RecordingFake(),
    ApiKeyRepository {
    val keys = MutableStateFlow(initial)
    private var nextId = (initial?.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeApiKeys(): Flow<List<ApiKey>?> = keys

    override suspend fun refresh() {
        record("refresh")
    }

    override suspend fun create(draft: ApiKeyDraft): CreatedApiKey {
        record("create:${draft.name}")
        val id = nextId++
        val status = if (draft.active) ApiKeyStatus.ACTIVE else ApiKeyStatus.INACTIVE
        keys.update { it.orEmpty() + ApiKey(id, draft.name, draft.active, status, expires = draft.expires) }
        return CreatedApiKey(id, "uk${id}_secretsecret")
    }

    override suspend fun setActive(
        id: Long,
        active: Boolean,
    ) {
        record("setActive:$id:$active")
        val status = if (active) ApiKeyStatus.ACTIVE else ApiKeyStatus.INACTIVE
        keys.update { list -> list?.map { if (it.id == id) it.copy(active = active, status = status) else it } }
    }

    override suspend fun delete(id: Long) {
        record("delete:$id")
        keys.update { list -> list?.filterNot { it.id == id } }
    }
}

/** In-memory [ProxyRepository]. */
class FakeProxyRepository(
    initial: List<Proxy>? = emptyList(),
) : RecordingFake(),
    ProxyRepository {
    val proxies = MutableStateFlow(initial)
    val saved = mutableListOf<ProxyDraft>()
    private var nextId = (initial?.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeProxies(): Flow<List<Proxy>?> = proxies

    override suspend fun save(draft: ProxyDraft): Long {
        record("save")
        saved += draft
        val id = draft.id ?: nextId++
        val proxy =
            Proxy(
                id,
                draft.protocol,
                draft.host,
                draft.port,
                draft.auth,
                draft.username,
                draft.password,
                draft.active,
                draft.isDefault,
            )
        proxies.update { list ->
            val others =
                list.orEmpty().filterNot { it.id == id }.map {
                    if (draft.isDefault) {
                        it.copy(
                            isDefault = false,
                        )
                    } else {
                        it
                    }
                }
            (others + proxy).sortedBy { it.id }
        }
        return id
    }

    override suspend fun delete(id: Long) {
        record("delete:$id")
        proxies.update { list -> list?.filterNot { it.id == id } }
    }
}

/** In-memory [DockerHostRepository]. */
class FakeDockerHostRepository(
    initial: List<DockerHost>? = emptyList(),
) : RecordingFake(),
    DockerHostRepository {
    val hosts = MutableStateFlow(initial)
    var testMessage: String? = "Connected Successfully. Amount of containers: 3"
    private var nextId = (initial?.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeDockerHosts(): Flow<List<DockerHost>?> = hosts

    override suspend fun save(draft: DockerHostDraft): Long {
        record("save:${draft.name}")
        val id = draft.id ?: nextId++
        hosts.update { list ->
            list.orEmpty().filterNot { it.id == id } +
                DockerHost(id, draft.name, draft.type, draft.daemon)
        }
        return id
    }

    override suspend fun test(draft: DockerHostDraft): String? {
        record("test:${draft.daemon}")
        return testMessage
    }

    override suspend fun delete(id: Long) {
        record("delete:$id")
        hosts.update { list -> list?.filterNot { it.id == id } }
    }
}

/** In-memory [RemoteBrowserRepository]. */
class FakeRemoteBrowserRepository(
    initial: List<RemoteBrowser>? = emptyList(),
) : RecordingFake(),
    RemoteBrowserRepository {
    val browsers = MutableStateFlow(initial)
    var testMessage: String? = "Connected Successfully."
    private var nextId = (initial?.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeRemoteBrowsers(): Flow<List<RemoteBrowser>?> = browsers

    override suspend fun save(draft: RemoteBrowserDraft): Long {
        record("save:${draft.name}")
        val id = draft.id ?: nextId++
        browsers.update { list -> list.orEmpty().filterNot { it.id == id } + RemoteBrowser(id, draft.name, draft.url) }
        return id
    }

    override suspend fun test(draft: RemoteBrowserDraft): String? {
        record("test:${draft.url}")
        return testMessage
    }

    override suspend fun delete(id: Long) {
        record("delete:$id")
        browsers.update { list -> list?.filterNot { it.id == id } }
    }
}

/** In-memory [CloudflaredRepository]. */
class FakeCloudflaredRepository(
    initial: CloudflaredState = CloudflaredState(installed = true),
) : RecordingFake(),
    CloudflaredRepository {
    val state = MutableStateFlow(initial)

    override fun observeState(): Flow<CloudflaredState> = state

    override suspend fun join() {
        record("join")
    }

    override suspend fun leave() {
        record("leave")
    }

    override suspend fun start(token: String) {
        record("start:$token")
        state.update { it.copy(running = true, token = token) }
    }

    override suspend fun stop(currentPassword: String?) {
        record("stop:$currentPassword")
        state.update { it.copy(running = false) }
    }

    override suspend fun removeToken() {
        record("removeToken")
        state.update { it.copy(token = null) }
    }
}
