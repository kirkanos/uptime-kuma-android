package net.kirkanos.uptimekuma.feature.monitors.editor

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.domain.TagSyncException
import net.kirkanos.uptimekuma.core.domain.editor.FieldIssue
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.ServerInfo
import net.kirkanos.uptimekuma.core.testing.FakeMonitorEditorRepository
import net.kirkanos.uptimekuma.core.testing.FakeServerInfoRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.FakeTagRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorEditorViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val spec = MonitorSpec.bundled
    private val existing =
        MonitorDrafts
            .newDraft(spec, "http")
            .with("name", "Website")
            .with("url", "https://example.com")
            .copy(id = 1, tags = listOf(MonitorTag(1, "env", "#059669", "prod")))
    private val repo = FakeMonitorEditorRepository(mapOf(1L to existing.with("id", 1L)))
    private val tags = FakeTagRepository()
    private val session = FakeSessionRepository(SampleData.serverConfig)
    private val info =
        FakeServerInfoRepository(ServerInfo(version = "2.5.8", primaryBaseUrl = "https://status.example.org"))

    private fun TestScope.viewModel(vararg args: Pair<String, Any?>): MonitorEditorViewModel {
        val vm = MonitorEditorViewModel(SavedStateHandle(mapOf(*args)), repo, tags, session, info)
        vm.load("Copy of %s")
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `new monitor starts with defaults and default notification`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_TYPE to "http")
            val state = vm.state.value
            assertEquals(EditorMode.NEW, state.mode)
            assertFalse(state.loading)
            assertNull(state.draft?.id)
            assertEquals(mapOf("1" to true), state.draft?.get("notificationIDList"))
            assertFalse(state.dirty)
            assertEquals(1, tags.refreshCount)
            assertTrue(state.sections.any { it.kind == SectionKind.AUTH })
            assertTrue(state.sections.last().kind == SectionKind.TAGS)
        }

    @Test
    fun `save of a new monitor validates first, then saves and reports the id`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_TYPE to "keyword")
            vm.save()
            var state = vm.state.value
            assertEquals(EditorProblem.Invalid, state.problem)
            assertTrue(state.showErrors)
            assertEquals(FieldIssue.Code.REQUIRED, state.visibleErrors()["keyword"]?.code)
            assertTrue(repo.saved.isEmpty())

            vm.onFieldChange("url", "https://example.com")
            vm.onFieldChange("keyword", "Example")
            assertTrue(vm.state.value.dirty)
            vm.save()
            advanceUntilIdle()
            state = vm.state.value
            assertEquals(2L, state.savedMonitorId)
            assertEquals("keyword", repo.saved.single().type)
            assertNull(state.problem)
        }

    @Test
    fun `short interval needs confirmation`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_MONITOR_ID to 1L)
            vm.onFieldChange("interval", "10")
            assertEquals(
                8L,
                vm.state.value.draft
                    ?.get("timeout"),
            )
            vm.save()
            assertTrue(vm.state.value.confirmShortInterval)
            assertTrue(repo.saved.isEmpty())
            vm.save(confirmedShortInterval = true)
            advanceUntilIdle()
            assertEquals(1L, vm.state.value.savedMonitorId)
            assertEquals("10", repo.saved.single()["interval"])
        }

    @Test
    fun `edit loads the monitor and tags can be changed`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_MONITOR_ID to 1L)
            assertEquals(EditorMode.EDIT, vm.state.value.mode)
            assertEquals(
                "Website",
                vm.state.value.draft
                    ?.name,
            )
            vm.removeTag(MonitorTag(1, "env", "#059669", "prod"))
            vm.addTag(SampleData.tagTeam, " android ")
            vm.addTag(SampleData.tagTeam, "android")
            assertEquals(
                listOf(MonitorTag(2, "team", "#7C3AED", "android")),
                vm.state.value.draft
                    ?.tags,
            )
            vm.createTag("new", "#DB2777", "x")
            advanceUntilIdle()
            assertEquals(
                "new",
                vm.state.value.draft
                    ?.tags
                    ?.last()
                    ?.name,
            )
            assertTrue(tags.tags.value.any { it.name == "new" })
        }

    @Test
    fun `clone uses the localized name and a fresh identity`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_CLONE_OF to 1L)
            val draft = vm.state.value.draft!!
            assertEquals(EditorMode.CLONE, vm.state.value.mode)
            assertEquals("Copy of Website", draft.name)
            assertNull(draft.id)
        }

    @Test
    fun `push monitor shows the push url with the primary base url`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_TYPE to "push")
            val token =
                vm.state.value.draft
                    ?.get("pushToken") as String
            assertEquals("https://status.example.org/api/push/$token?status=up&msg=OK&ping=", vm.state.value.pushUrl)
            vm.regeneratePushToken()
            assertFalse(
                vm.state.value.pushUrl!!
                    .contains(token),
            )
        }

    @Test
    fun `server errors are shown and tag failures switch to edit mode`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_TYPE to "group")
            vm.onFieldChange("name", "G")
            repo.saveError = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Invalid Monitor Group"))
            vm.save()
            advanceUntilIdle()
            val problem = vm.state.value.problem as EditorProblem.Server
            assertEquals("Invalid Monitor Group", problem.error.message)
            assertNull(vm.state.value.savedMonitorId)

            repo.saveError = TagSyncException(42, KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "boom")))
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.problem is EditorProblem.TagsFailed)
            assertEquals(
                42L,
                vm.state.value.draft
                    ?.id,
            )
        }

    @Test
    fun `load errors can be retried`() =
        runTest(mainDispatcher.testDispatcher) {
            repo.error = KumaException(KumaError(KumaError.Kind.NETWORK))
            val vm = viewModel(MonitorEditorRoute.ARG_MONITOR_ID to 1L)
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.loadError
                    ?.kind,
            )
            repo.error = null
            vm.retryLoad()
            advanceUntilIdle()
            assertNull(vm.state.value.loadError)
            assertEquals(
                "Website",
                vm.state.value.draft
                    ?.name,
            )
        }

    @Test
    fun `delete removes the monitor`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(MonitorEditorRoute.ARG_MONITOR_ID to 1L)
            vm.delete(deleteChildren = true)
            advanceUntilIdle()
            assertTrue(vm.state.value.deleted)
            assertEquals(listOf(1L to true), repo.deleted)
        }

    @Test
    fun `parent picker excludes the monitor and its children`() =
        runTest(mainDispatcher.testDispatcher) {
            repo.stored[1L] = existing.with("childrenIDs", listOf(11L, 12L))
            val vm = viewModel(MonitorEditorRoute.ARG_MONITOR_ID to 1L)
            assertEquals(setOf(1L, 11L, 12L), vm.state.value.excludedParents)
        }
}
