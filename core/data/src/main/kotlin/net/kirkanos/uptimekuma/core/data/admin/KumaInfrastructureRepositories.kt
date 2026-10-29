package net.kirkanos.uptimekuma.core.data.admin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.domain.ApiKeyRepository
import net.kirkanos.uptimekuma.core.domain.DockerHostRepository
import net.kirkanos.uptimekuma.core.domain.ProxyRepository
import net.kirkanos.uptimekuma.core.domain.RemoteBrowserRepository
import net.kirkanos.uptimekuma.core.kumaclient.KumaAcks
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyDraft
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Snapshot-backed repositories for API keys, proxies, Docker hosts and remote browsers (socket-events.md 3.10).
 * The server re-pushes the list after every change, so the acks are only checked for `ok` and the id.
 */

private fun JsonObject.requireId(event: String): Long =
    this["id"].asLenientLong() ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "$event without id"))

/** Message of a test ack (`{ok:true, msg:"Connected Successfully..."}`). */
private fun JsonObject.message(): String? = KumaAcks.messageOf(this) ?: this["msg"].asStringOrNull()

@Singleton
internal class KumaApiKeyRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : ApiKeyRepository {
        override fun observeApiKeys(): Flow<List<ApiKey>?> =
            backend.snapshot("apiKeyList").map(AdminJson::decodeApiKeys).distinctUntilChanged()

        override suspend fun refresh() {
            backend.call("getAPIKeyList")
        }

        override suspend fun create(draft: ApiKeyDraft): CreatedApiKey {
            val ack = backend.call("addAPIKey", AdminJson.apiKeyPayload(draft))
            val key =
                ack["key"].asStringOrNull()
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "addAPIKey without key"))
            val id =
                ack["keyID"].asLenientLong()
                    ?: key.removePrefix("uk").substringBefore('_').toLongOrNull()
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "addAPIKey without keyID"))
            return CreatedApiKey(id, key)
        }

        override suspend fun setActive(
            id: Long,
            active: Boolean,
        ) {
            backend.call(if (active) "enableAPIKey" else "disableAPIKey", id)
        }

        override suspend fun delete(id: Long) {
            backend.call("deleteAPIKey", id)
        }
    }

@Singleton
internal class KumaProxyRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : ProxyRepository {
        override fun observeProxies(): Flow<List<Proxy>?> =
            backend.snapshot("proxyList").map(AdminJson::decodeProxies).distinctUntilChanged()

        override suspend fun save(draft: ProxyDraft): Long =
            backend.call("addProxy", AdminJson.proxyPayload(draft), draft.id).requireId("addProxy")

        override suspend fun delete(id: Long) {
            backend.call("deleteProxy", id)
        }
    }

@Singleton
internal class KumaDockerHostRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : DockerHostRepository {
        override fun observeDockerHosts(): Flow<List<DockerHost>?> =
            backend.snapshot("dockerHostList").map(AdminJson::decodeDockerHosts).distinctUntilChanged()

        override suspend fun save(draft: DockerHostDraft): Long =
            backend.call("addDockerHost", AdminJson.dockerHostPayload(draft), draft.id).requireId("addDockerHost")

        override suspend fun test(draft: DockerHostDraft): String? =
            backend
                .call(
                    "testDockerHost",
                    AdminJson.dockerHostPayload(draft),
                    timeoutMillis = TEST_TIMEOUT_MILLIS,
                ).message()

        override suspend fun delete(id: Long) {
            backend.call("deleteDockerHost", id)
        }
    }

@Singleton
internal class KumaRemoteBrowserRepository
    @Inject
    constructor(
        private val backend: AdminBackend,
    ) : RemoteBrowserRepository {
        override fun observeRemoteBrowsers(): Flow<List<RemoteBrowser>?> =
            backend.snapshot("remoteBrowserList").map(AdminJson::decodeRemoteBrowsers).distinctUntilChanged()

        override suspend fun save(draft: RemoteBrowserDraft): Long =
            backend
                .call("addRemoteBrowser", AdminJson.remoteBrowserPayload(draft), draft.id)
                .requireId("addRemoteBrowser")

        override suspend fun test(draft: RemoteBrowserDraft): String? =
            backend
                .call("testRemoteBrowser", AdminJson.remoteBrowserPayload(draft), timeoutMillis = TEST_TIMEOUT_MILLIS)
                .message()

        override suspend fun delete(id: Long) {
            backend.call("deleteRemoteBrowser", id)
        }
    }

/** Docker/browser connection tests wait for the server's own timeouts. */
private const val TEST_TIMEOUT_MILLIS = 45_000L
