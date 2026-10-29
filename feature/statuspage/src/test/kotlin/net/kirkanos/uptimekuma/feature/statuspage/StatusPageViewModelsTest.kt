package net.kirkanos.uptimekuma.feature.statuspage

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.OverallStatus
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageAnalytics
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.FakeStatusPageRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import net.kirkanos.uptimekuma.feature.statuspage.detail.StatusPageDetailViewModel
import net.kirkanos.uptimekuma.feature.statuspage.edit.StatusPageEditViewModel
import net.kirkanos.uptimekuma.feature.statuspage.list.StatusPageListViewModel
import net.kirkanos.uptimekuma.feature.statuspage.publicview.PublicStatusPageViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class StatusPageViewModelsTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val now = 1_761_700_000_000L
    private val repo = FakeStatusPageRepository.withSampleData(now)
    private val session = FakeSessionRepository(SampleData.serverConfig)
    private val monitors = FakeMonitorRepository.withSampleData(now)

    private fun TestScope.listVm() =
        StatusPageListViewModel(repo, session).also {
            backgroundScope.launch { it.state.collect {} }
            advanceUntilIdle()
        }

    private fun TestScope.detailVm(slug: String = "status") =
        StatusPageDetailViewModel(SavedStateHandle(mapOf(StatusPageDetailRoute.ARG_SLUG to slug)), repo, session).also {
            backgroundScope.launch { it.state.collect {} }
            advanceUntilIdle()
        }

    private fun TestScope.editVm(slug: String = "status") =
        StatusPageEditViewModel(SavedStateHandle(mapOf(StatusPageEditRoute.ARG_SLUG to slug)), repo, monitors).also {
            backgroundScope.launch { it.state.collect {} }
            advanceUntilIdle()
        }

    private fun TestScope.publicVm(url: String) =
        PublicStatusPageViewModel(SavedStateHandle(mapOf(PublicStatusPageRoute.ARG_URL to url)), repo).also {
            backgroundScope.launch { it.state.collect {} }
            advanceUntilIdle()
        }

    // ---- list -----------------------------------------------------------------------------------

    @Test
    fun `list shows pages and requests a refresh`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = listVm()
            assertEquals(
                listOf("status"),
                vm.state.value.pages
                    ?.map { it.slug },
            )
            assertEquals(1, repo.refreshCount)
            assertEquals("https://uptime.example.org/status/", vm.state.value.publicUrlPrefill)
            assertEquals("https://uptime.example.org/status/status", vm.state.value.webUrl("status"))
        }

    @Test
    fun `list stays loading until the snapshot arrives and shows refresh errors`() =
        runTest(mainDispatcher.testDispatcher) {
            repo.pages.value = null
            repo.error = KumaException(KumaError(KumaError.Kind.UNAUTHORIZED))
            val vm = listVm()
            assertEquals(
                KumaError.Kind.UNAUTHORIZED,
                vm.state.value.loadError
                    ?.kind,
            )
            repo.error = null
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(emptyList<Any>(), vm.state.value.pages)
        }

    @Test
    fun `create dialog derives the slug from the title until edited and creates the page`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = listVm()
            vm.openCreate()
            vm.onCreateTitle("[P2-STATUS] Test")
            advanceUntilIdle()
            assertEquals(
                "p2-status-test",
                vm.state.value.create
                    ?.slug,
            )
            vm.onCreateSlug("My Slug")
            vm.onCreateTitle("Other")
            advanceUntilIdle()
            val create = vm.state.value.create!!
            assertEquals("my-slug", create.slug)
            assertTrue(create.canSubmit)
            vm.submitCreate()
            advanceUntilIdle()
            assertEquals(listOf("Other" to "my-slug"), repo.created)
            assertEquals(
                "my-slug",
                vm.state.value.create
                    ?.createdSlug,
            )
            vm.consumeCreated()
            advanceUntilIdle()
            assertNull(vm.state.value.create)
        }

    @Test
    fun `invalid slug blocks create and server errors are kept`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = listVm()
            vm.openCreate()
            vm.onCreateTitle("x")
            vm.onCreateSlug("a--b")
            advanceUntilIdle()
            assertEquals(
                StatusPageSlug.Problem.CONSECUTIVE_OR_EDGE_DASH,
                vm.state.value.create
                    ?.slugProblem,
            )
            vm.submitCreate()
            advanceUntilIdle()
            assertTrue(repo.created.isEmpty())
            vm.onCreateSlug("status")
            vm.submitCreate()
            advanceUntilIdle()
            assertTrue(
                vm.state.value.create
                    ?.error
                    ?.message
                    .orEmpty()
                    .contains("UNIQUE constraint"),
            )
        }

    // ---- detail ---------------------------------------------------------------------------------

    @Test
    fun `detail loads page, heartbeats and history`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = detailVm()
            val state = vm.state.value
            assertFalse(state.view.loading)
            assertEquals(
                "Example status",
                state.view.page
                    ?.config
                    ?.title,
            )
            assertEquals(OverallStatus.PARTIAL_DOWN, state.view.heartbeats.overallStatus)
            assertEquals(
                "Degraded API performance",
                state.view.page
                    ?.incident
                    ?.title,
            )
            assertEquals(1, state.view.history.items.size)
            assertEquals("https://uptime.example.org/status/status", state.webUrl)
            assertEquals(true, state.config?.published)
            assertEquals(listOf<Pair<String, StatusPageAddress?>>("status" to null), repo.publicRequests)
        }

    @Test
    fun `detail error and retry`() =
        runTest(mainDispatcher.testDispatcher) {
            repo.publicError = KumaException(KumaError(KumaError.Kind.NETWORK))
            val vm = detailVm()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.view.error
                    ?.kind,
            )
            assertNull(vm.state.value.view.page)
            repo.publicError = null
            vm.refresh()
            advanceUntilIdle()
            assertNotNull(vm.state.value.view.page)
            assertNull(vm.state.value.view.error)
        }

    @Test
    fun `post and unpin incident update the page and history`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = detailVm()
            vm.postIncident(IncidentDraft("Outage", "We are on it", IncidentStyle.DANGER))
            advanceUntilIdle()
            assertEquals(
                "status" to IncidentDraft("Outage", "We are on it", IncidentStyle.DANGER),
                repo.postedIncidents.single(),
            )
            assertEquals(
                "Outage",
                vm.state.value.view.page
                    ?.incident
                    ?.title,
            )
            assertEquals(1, vm.state.value.incidentPosted)
            assertEquals(2, vm.state.value.view.history.items.size)
            vm.unpinIncident()
            advanceUntilIdle()
            assertEquals(listOf("status"), repo.unpinned)
            assertNull(
                vm.state.value.view.page
                    ?.incident,
            )
            assertNull(vm.state.value.actionInProgress)
        }

    @Test
    fun `failed action keeps the page and reports the error once`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = detailVm()
            repo.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "slug is not found"))
            vm.unpinIncident()
            advanceUntilIdle()
            assertEquals(
                "slug is not found",
                vm.state.value.actionError
                    ?.message,
            )
            assertNotNull(
                vm.state.value.view.page
                    ?.incident,
            )
            vm.dismissActionError()
            advanceUntilIdle()
            assertNull(vm.state.value.actionError)
        }

    @Test
    fun `delete marks the screen as done`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = detailVm()
            vm.state.test {
                skipItems(1)
                vm.delete()
                advanceUntilIdle()
                assertTrue(expectMostRecentItem().deleted)
            }
            assertEquals(listOf("status"), repo.deleted)
            assertEquals(emptyList<Any>(), repo.pages.value)
        }

    @Test
    fun `history pages with the cursor`() =
        runTest(mainDispatcher.testDispatcher) {
            repo.history["status"] =
                List(15) { i ->
                    repo.history
                        .getValue("status")
                        .first()
                        .copy(id = i.toLong())
                }
            val vm = detailVm()
            assertEquals(10, vm.state.value.view.history.items.size)
            assertTrue(vm.state.value.view.history.hasMore)
            vm.loadMoreHistory()
            advanceUntilIdle()
            assertEquals(15, vm.state.value.view.history.items.size)
            assertFalse(vm.state.value.view.history.hasMore)
        }

    // ---- edit -----------------------------------------------------------------------------------

    @Test
    fun `edit loads config and groups and saves the edited page`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm()
            val form = vm.state.value.form!!
            assertEquals("Example status", form.config.title)
            assertEquals(listOf("Services", "Infrastructure"), form.groups.map { it.name })
            assertFalse(vm.state.value.dirty)

            vm.updateConfig { it.copy(title = "New", slug = "New-Slug", analyticsType = StatusPageAnalytics.UMAMI) }
            vm.setRefreshText("60")
            vm.addDomain()
            vm.setDomain(0, " b.example ")
            vm.renameGroup(1, "Infra")
            vm.moveGroup(1, -1)
            vm.addMonitors(0, listOf(4, 5, 1))
            vm.setSendUrl(0, 0, true)
            vm.setCustomUrl(0, 0, " https://db.example ")
            vm.moveMonitor(1, 0, 1)
            vm.removeMonitor(1, 0)
            advanceUntilIdle()
            assertTrue(vm.state.value.dirty)

            vm.save()
            advanceUntilIdle()
            val (slug, config, groups) = repo.saved.single()
            assertEquals("status", slug, "saved under the current slug")
            assertEquals("new-slug", config.slug)
            assertEquals("New", config.title)
            assertEquals(60, config.autoRefreshInterval)
            assertEquals(listOf("b.example"), config.domainNameList)
            assertEquals(StatusPageAnalytics.UMAMI, config.analyticsType)
            assertEquals(listOf("Infra", "Services"), groups.map { it.name })
            assertEquals(listOf(3L, 4L, 5L), groups[0].monitors.map { it.id }, "monitor 1 is already on the page")
            assertEquals(true, groups[0].monitors[0].sendUrl)
            assertEquals("https://db.example", groups[0].monitors[0].url)
            assertEquals(listOf(1L), groups[1].monitors.map { it.id })
            assertEquals("new-slug", vm.state.value.savedSlug)
            assertFalse(vm.state.value.dirty)
        }

    @Test
    fun `picker offers only monitors that are not on the page`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm()
            val available =
                vm.state.value.availableMonitors
                    .map { it.id }
            assertFalse(available.contains(1L))
            assertFalse(available.contains(3L))
            assertTrue(available.contains(4L))
        }

    @Test
    fun `groups can be added at the top and deleted`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm()
            vm.addGroup("Untitled Group")
            advanceUntilIdle()
            assertEquals(
                "Untitled Group",
                vm.state.value.form!!
                    .groups
                    .first()
                    .name,
            )
            assertNull(
                vm.state.value.form!!
                    .groups
                    .first()
                    .id,
            )
            vm.deleteGroup(0)
            vm.deleteGroup(0)
            advanceUntilIdle()
            assertEquals(
                listOf("Infrastructure"),
                vm.state.value.form!!
                    .groups
                    .map { it.name },
            )
        }

    @Test
    fun `invalid form is not saved`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm()
            vm.updateConfig { it.copy(title = " ") }
            vm.setRefreshText("3")
            vm.save()
            advanceUntilIdle()
            assertTrue(repo.saved.isEmpty())
            assertTrue(vm.state.value.showValidation)
            assertTrue(
                vm.state.value.form!!
                    .titleMissing,
            )
            assertNull(
                vm.state.value.form!!
                    .refreshSeconds,
            )
        }

    @Test
    fun `save error is shown and the form kept`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm()
            repo.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Invalid analytics type"))
            vm.save()
            advanceUntilIdle()
            assertEquals(
                "Invalid analytics type",
                vm.state.value.saveError
                    ?.message,
            )
            assertNull(vm.state.value.savedSlug)
            vm.dismissSaveError()
            advanceUntilIdle()
            assertNull(vm.state.value.saveError)
        }

    @Test
    fun `edit load error`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = editVm("missing")
            assertNull(vm.state.value.form)
            assertEquals(
                "Status Page Not Found",
                vm.state.value.loadError
                    ?.message,
            )
        }

    // ---- public viewer --------------------------------------------------------------------------

    @Test
    fun `public viewer parses the link and loads over REST with the address`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = publicVm("https://other.example/kuma/status/status")
            val state = vm.state.value
            assertEquals(
                StatusPageAddress("https://other.example/kuma", "status"),
                state.address,
                "slug may be \"status\" too",
            )
            assertEquals(
                "Example status",
                state.view.page
                    ?.config
                    ?.title,
            )
            assertEquals(
                listOf("status" to StatusPageAddress("https://other.example/kuma", "status")),
                repo.publicRequests,
            )
            assertEquals(1, state.view.history.items.size)
        }

    @Test
    fun `public viewer rejects links without a status path`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = publicVm("https://other.example/dashboard")
            assertTrue(vm.state.value.invalidUrl)
            assertFalse(vm.state.value.view.loading)
            assertTrue(repo.publicRequests.isEmpty())
        }
}
