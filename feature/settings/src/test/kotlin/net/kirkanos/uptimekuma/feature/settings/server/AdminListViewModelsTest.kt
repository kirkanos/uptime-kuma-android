package net.kirkanos.uptimekuma.feature.settings.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.ApiKey
import net.kirkanos.uptimekuma.core.model.ApiKeyStatus
import net.kirkanos.uptimekuma.core.model.CloudflaredState
import net.kirkanos.uptimekuma.core.model.CreatedApiKey
import net.kirkanos.uptimekuma.core.model.DockerHostDraft
import net.kirkanos.uptimekuma.core.model.DockerHostType
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.Proxy
import net.kirkanos.uptimekuma.core.model.ProxyDraft
import net.kirkanos.uptimekuma.core.model.ProxyProtocol
import net.kirkanos.uptimekuma.core.model.RemoteBrowserDraft
import net.kirkanos.uptimekuma.core.testing.FakeApiKeyRepository
import net.kirkanos.uptimekuma.core.testing.FakeCloudflaredRepository
import net.kirkanos.uptimekuma.core.testing.FakeDockerHostRepository
import net.kirkanos.uptimekuma.core.testing.FakeProxyRepository
import net.kirkanos.uptimekuma.core.testing.FakeRemoteBrowserRepository
import net.kirkanos.uptimekuma.core.testing.FakeServerSettingsRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.settings.server.apikeys.ApiKeysViewModel
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.history.DatabaseOperation
import net.kirkanos.uptimekuma.feature.settings.server.history.DatabaseViewModel
import net.kirkanos.uptimekuma.feature.settings.server.infra.DockerHostsViewModel
import net.kirkanos.uptimekuma.feature.settings.server.infra.InfraValidation
import net.kirkanos.uptimekuma.feature.settings.server.infra.ProxiesViewModel
import net.kirkanos.uptimekuma.feature.settings.server.infra.RemoteBrowsersViewModel
import net.kirkanos.uptimekuma.feature.settings.server.infra.TestResult
import net.kirkanos.uptimekuma.feature.settings.server.proxy.CloudflaredViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AdminListViewModelsTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch { flow.collect {} }
        advanceUntilIdle()
    }

    // ---- API keys ---------------------------------------------------------------------------------

    @Test
    fun `api keys - create shows the key once, toggle and delete`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeApiKeyRepository(listOf(ApiKey(1, "Grafana", true, ApiKeyStatus.ACTIVE)))
            val vm = ApiKeysViewModel(repo)
            vm.clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
            collect(vm.state)
            assertEquals("refresh", repo.calls.first())
            assertEquals(
                1,
                vm.state.value.keys
                    ?.size,
            )

            vm.startCreate()
            advanceUntilIdle()
            val form = vm.state.value.form!!
            assertEquals(LocalDateTime.of(2026, 11, 29, 10, 0), form.expires)
            assertFalse(vm.canCreate(form))
            vm.updateForm { it.copy(name = "Prometheus", expires = LocalDateTime.of(2020, 1, 1, 0, 0)) }
            advanceUntilIdle()
            assertFalse(vm.canCreate(vm.state.value.form!!))
            vm.updateForm { it.copy(expires = null) }
            advanceUntilIdle()
            assertTrue(vm.canCreate(vm.state.value.form!!))
            vm.create()
            advanceUntilIdle()
            assertNull(vm.state.value.form)
            assertEquals(CreatedApiKey(2, "uk2_secretsecret"), vm.state.value.created)
            vm.dismissCreated()
            advanceUntilIdle()
            assertNull(vm.state.value.created)

            vm.setActive(2, false)
            advanceUntilIdle()
            assertEquals(
                ApiKeyStatus.INACTIVE,
                vm.state.value.keys
                    ?.first { it.id == 2L }
                    ?.status,
            )
            assertEquals(AdminMessage.Done(AdminDone.DISABLED), vm.state.value.message)
            vm.delete(1)
            advanceUntilIdle()
            assertEquals(
                listOf(2L),
                vm.state.value.keys
                    ?.map { it.id },
            )
            assertEquals(AdminMessage.Done(AdminDone.DELETED), vm.state.value.message)
        }

    @Test
    fun `api keys - create failure stays in the form`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeApiKeyRepository()
            val vm = ApiKeysViewModel(repo)
            collect(vm.state)
            vm.startCreate()
            advanceUntilIdle()
            vm.updateForm { it.copy(name = "x", expires = null) }
            advanceUntilIdle()
            repo.failWith = KumaError(KumaError.Kind.SERVER_REJECTED, "nope")
            vm.create()
            advanceUntilIdle()
            val form = vm.state.value.form!!
            assertFalse(form.saving)
            assertEquals("nope", form.error?.message)
            assertNull(vm.state.value.created)
        }

    @Test
    fun `api keys - refresh failure is shown while there is no list`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeApiKeyRepository(initial = null).apply { failWith = KumaError(KumaError.Kind.NETWORK) }
            val vm = ApiKeysViewModel(repo)
            collect(vm.state)
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.loadError
                    ?.kind,
            )
        }

    // ---- proxies / docker / browsers ----------------------------------------------------------

    @Test
    fun `proxies - create, edit and delete`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo =
                FakeProxyRepository(listOf(Proxy(1, ProxyProtocol.HTTP, "a", 3128, auth = false, isDefault = true)))
            val vm = ProxiesViewModel(repo)
            collect(vm.state)
            vm.create()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.editor!!
                    .isNew,
            )
            assertFalse(vm.canSave(vm.state.value.editor!!))
            vm.updateDraft { it.copy(host = "b", port = 1080, protocol = ProxyProtocol.SOCKS5, isDefault = true) }
            advanceUntilIdle()
            assertTrue(vm.canSave(vm.state.value.editor!!))
            vm.save()
            advanceUntilIdle()
            assertNull(vm.state.value.editor)
            assertEquals(AdminMessage.Done(AdminDone.CREATED), vm.state.value.message)
            assertEquals(
                listOf(false, true),
                vm.state.value.items
                    ?.map { it.isDefault },
            )

            vm.edit(
                vm.state.value.items!!
                    .first(),
            )
            advanceUntilIdle()
            assertEquals(
                1L,
                vm.state.value.editor!!
                    .draft.id,
            )
            vm.updateDraft { it.copy(port = 8080) }
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()
            assertEquals(AdminMessage.Done(AdminDone.SAVED), vm.state.value.message)
            assertEquals(8080, repo.saved.last().port)

            vm.delete(1)
            advanceUntilIdle()
            assertEquals(
                listOf(2L),
                vm.state.value.items
                    ?.map { it.id },
            )
        }

    @Test
    fun `proxies - save failure stays in the editor`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeProxyRepository()
            val vm = ProxiesViewModel(repo)
            collect(vm.state)
            vm.create()
            advanceUntilIdle()
            vm.updateDraft { it.copy(host = "h", port = 1) }
            advanceUntilIdle()
            repo.failWith = KumaError(KumaError.Kind.SERVER_REJECTED, "x")
            vm.save()
            advanceUntilIdle()
            assertEquals(
                "x",
                vm.state.value.editor
                    ?.error
                    ?.message,
            )
            vm.dismissEditor()
            advanceUntilIdle()
            assertNull(vm.state.value.editor)
        }

    @Test
    fun `docker hosts - test inside the editor, then save`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeDockerHostRepository()
            val vm = DockerHostsViewModel(repo)
            collect(vm.state)
            vm.create()
            advanceUntilIdle()
            assertEquals(
                DockerHostDraft.DEFAULT_SOCKET,
                vm.state.value.editor!!
                    .draft.daemon,
            )
            vm.updateDraft { it.copy(name = "Local") }
            advanceUntilIdle()
            vm.runTest()
            advanceUntilIdle()
            advanceUntilIdle()
            assertEquals(
                TestResult.Ok("Connected Successfully. Amount of containers: 3"),
                vm.state.value.editor!!
                    .test,
            )
            repo.failWith = KumaError(KumaError.Kind.SERVER_REJECTED, "connect ENOENT /var/run/docker.sock")
            vm.runTest()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.editor!!
                    .test is TestResult.Failed,
            )
            // Editing the draft clears the old result.
            vm.updateDraft { it.copy(name = "Local 2") }
            advanceUntilIdle()
            assertNull(
                vm.state.value.editor!!
                    .test,
            )
            repo.failWith = null
            vm.save()
            advanceUntilIdle()
            assertEquals(
                "Local 2",
                vm.state.value.items
                    ?.single()
                    ?.name,
            )
        }

    @Test
    fun `remote browsers - create and delete`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeRemoteBrowserRepository()
            val vm = RemoteBrowsersViewModel(repo)
            collect(vm.state)
            vm.create()
            advanceUntilIdle()
            vm.updateDraft { RemoteBrowserDraft(name = "B", url = "ws://chrome:3000") }
            advanceUntilIdle()
            vm.runTest()
            advanceUntilIdle()
            assertEquals(
                TestResult.Ok("Connected Successfully."),
                vm.state.value.editor!!
                    .test,
            )
            vm.save()
            advanceUntilIdle()
            val id =
                vm.state.value.items!!
                    .single()
                    .id
            vm.delete(id)
            advanceUntilIdle()
            assertEquals(emptyList<Any>(), vm.state.value.items)
            assertEquals(listOf("test:ws://chrome:3000", "save:B", "delete:$id"), repo.calls)
        }

    @Test
    fun `editor validation`() {
        assertTrue(InfraValidation.proxy(ProxyDraft(host = "h", port = 65535)))
        assertFalse(InfraValidation.proxy(ProxyDraft(host = "h", port = 0)))
        assertFalse(InfraValidation.proxy(ProxyDraft(host = "h", port = null)))
        assertFalse(InfraValidation.proxy(ProxyDraft(host = " ", port = 1)))
        assertFalse(InfraValidation.proxy(ProxyDraft(host = "h", port = 1, auth = true)))
        assertTrue(InfraValidation.proxy(ProxyDraft(host = "h", port = 1, auth = true, username = "u")))
        assertTrue(InfraValidation.dockerHost(DockerHostDraft(name = "d")))
        assertFalse(
            InfraValidation.dockerHost(DockerHostDraft(name = "d", type = DockerHostType.TCP, daemon = "host:2375")),
        )
        assertTrue(
            InfraValidation.dockerHost(
                DockerHostDraft(name = "d", type = DockerHostType.TCP, daemon = "tcp://host:2375"),
            ),
        )
        assertFalse(
            InfraValidation.dockerHost(DockerHostDraft(name = "d", type = DockerHostType.SOCKET, daemon = "tcp://x")),
        )
        assertFalse(InfraValidation.dockerHost(DockerHostDraft(name = "")))
        assertTrue(InfraValidation.remoteBrowser(RemoteBrowserDraft(name = "b", url = "wss://x")))
        assertFalse(InfraValidation.remoteBrowser(RemoteBrowserDraft(name = "b", url = "http://x")))
    }

    // ---- database / cloudflared ---------------------------------------------------------------

    @Test
    fun `database - size, shrink and clear statistics`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeServerSettingsRepository()
            val vm = DatabaseViewModel(repo)
            advanceUntilIdle()
            assertEquals(1_048_576L, vm.state.value.sizeBytes)
            vm.shrink()
            assertEquals(DatabaseOperation.SHRINK, vm.state.value.running)
            vm.clearStatistics() // ignored while busy
            advanceUntilIdle()
            assertNull(vm.state.value.running)
            assertEquals(524_288L, vm.state.value.sizeBytes)
            assertEquals(AdminMessage.Done(AdminDone.DATABASE_SHRUNK), vm.state.value.message)
            vm.clearStatistics()
            advanceUntilIdle()
            assertEquals(AdminMessage.Done(AdminDone.STATISTICS_CLEARED), vm.state.value.message)
            assertEquals(
                listOf("databaseSize", "shrinkDatabase", "databaseSize", "clearStatistics", "databaseSize"),
                repo.calls,
            )
        }

    @Test
    fun `database - size error`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeServerSettingsRepository().apply { failWith = KumaError(KumaError.Kind.TIMEOUT) }
            val vm = DatabaseViewModel(repo)
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.TIMEOUT,
                vm.state.value.sizeError
                    ?.kind,
            )
        }

    @Test
    fun `cloudflared - joins, prefills the token, starts and stops`() =
        runTest(mainDispatcher.testDispatcher) {
            val repo = FakeCloudflaredRepository(CloudflaredState(installed = true, token = "stored"))
            val vm = CloudflaredViewModel(repo)
            collect(vm.state)
            assertEquals("join", repo.calls.first())
            assertEquals("stored", vm.state.value.tokenInput)
            assertTrue(vm.state.value.canStart)
            vm.setToken(" new ")
            advanceUntilIdle()
            vm.start()
            advanceUntilIdle()
            assertEquals("start:new", repo.calls.last())
            assertTrue(vm.state.value.tunnel.running)
            assertEquals(AdminMessage.Done(AdminDone.TUNNEL_STARTING), vm.state.value.message)
            vm.stop("pw")
            advanceUntilIdle()
            assertEquals("stop:pw", repo.calls.last())
            vm.removeToken()
            advanceUntilIdle()
            assertEquals("", vm.state.value.tokenInput)
            assertFalse(vm.state.value.canStart)
        }

    @Test
    fun `cloudflared - not installed cannot start`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = CloudflaredViewModel(FakeCloudflaredRepository(CloudflaredState(installed = false)))
            collect(vm.state)
            vm.setToken("t")
            advanceUntilIdle()
            assertFalse(vm.state.value.canStart)
        }
}
