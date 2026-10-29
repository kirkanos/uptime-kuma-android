package net.kirkanos.uptimekuma.feature.settings.server.infra

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.domain.DockerHostRepository
import net.kirkanos.uptimekuma.core.domain.ProxyRepository
import net.kirkanos.uptimekuma.core.domain.RemoteBrowserRepository
import net.kirkanos.uptimekuma.core.model.DockerHost
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.RemoteBrowser
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import javax.inject.Inject

/** Field rules of the editor dialogs (mirroring the web UI's `required` inputs). */
internal object InfraValidation {
    private const val MAX_PORT = 65_535
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

    fun proxy(draft: ProxyDraft): Boolean =
        draft.host.isNotBlank() &&
            !draft.host.trim().contains(' ') &&
            draft.port?.let { it in 1..MAX_PORT } == true &&
            (!draft.auth || draft.username.isNotBlank())

    fun dockerHost(draft: DockerHostDraft): Boolean =
        draft.name.isNotBlank() &&
            draft.daemon.isNotBlank() &&
            when (draft.type) {
                DockerHostType.SOCKET -> !draft.daemon.contains("://")
                DockerHostType.TCP -> SCHEME.containsMatchIn(draft.daemon.trim())
            }

    fun remoteBrowser(draft: RemoteBrowserDraft): Boolean =
        draft.name.isNotBlank() && draft.url.trim().let { it.startsWith("ws://") || it.startsWith("wss://") }
}

@HiltViewModel
class ProxiesViewModel
    @Inject
    constructor(
        private val repository: ProxyRepository,
    ) : CrudListViewModel<Proxy, ProxyDraft>() {
        override fun observe(): Flow<List<Proxy>?> = repository.observeProxies()

        override fun newDraft(): ProxyDraft = ProxyDraft()

        override fun draftOf(item: Proxy): ProxyDraft =
            ProxyDraft(
                id = item.id,
                protocol = item.protocol,
                host = item.host,
                port = item.port,
                auth = item.auth,
                username = item.username,
                password = item.password,
                active = item.active,
                isDefault = item.isDefault,
            )

        override fun isValid(draft: ProxyDraft): Boolean = InfraValidation.proxy(draft)

        override suspend fun persist(draft: ProxyDraft): Long = repository.save(draft)

        override suspend fun remove(id: Long) = repository.delete(id)
    }

@HiltViewModel
class DockerHostsViewModel
    @Inject
    constructor(
        private val repository: DockerHostRepository,
    ) : CrudListViewModel<DockerHost, DockerHostDraft>() {
        override fun observe(): Flow<List<DockerHost>?> = repository.observeDockerHosts()

        override fun newDraft(): DockerHostDraft = DockerHostDraft()

        override fun draftOf(item: DockerHost): DockerHostDraft =
            DockerHostDraft(item.id, item.name, item.type, item.daemon)

        override fun isValid(draft: DockerHostDraft): Boolean = InfraValidation.dockerHost(draft)

        override suspend fun persist(draft: DockerHostDraft): Long = repository.save(draft)

        override suspend fun remove(id: Long) = repository.delete(id)

        override suspend fun test(draft: DockerHostDraft): String? = repository.test(draft)
    }

@HiltViewModel
class RemoteBrowsersViewModel
    @Inject
    constructor(
        private val repository: RemoteBrowserRepository,
    ) : CrudListViewModel<RemoteBrowser, RemoteBrowserDraft>() {
        override fun observe(): Flow<List<RemoteBrowser>?> = repository.observeRemoteBrowsers()

        override fun newDraft(): RemoteBrowserDraft = RemoteBrowserDraft()

        override fun draftOf(item: RemoteBrowser): RemoteBrowserDraft = RemoteBrowserDraft(item.id, item.name, item.url)

        override fun isValid(draft: RemoteBrowserDraft): Boolean = InfraValidation.remoteBrowser(draft)

        override suspend fun persist(draft: RemoteBrowserDraft): Long = repository.save(draft)

        override suspend fun remove(id: Long) = repository.delete(id)

        override suspend fun test(draft: RemoteBrowserDraft): String? = repository.test(draft)
    }
