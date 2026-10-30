package net.kirkanos.uptimekuma.feature.monitors.dashboard

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.AppUpdate
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.DisplayStatus
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.testing.FakeAppUpdateRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.core.testing.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val now = 1_761_700_000_000L
    private val monitors = FakeMonitorRepository.withSampleData(now)
    private val session = FakeSessionRepository(SampleData.serverConfig)
    private val updates = FakeAppUpdateRepository()

    private fun TestScope.collect(vm: DashboardViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun `emits loading first, then rows, counts and tags`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            vm.state.test {
                assertTrue(awaitItem().loading)
                val loaded = awaitItem()
                assertFalse(loaded.loading)
                assertEquals(SampleData.monitors(now).size, loaded.rows.size)
                assertEquals(1, loaded.counts[DisplayStatus.DOWN])
                assertEquals(1, loaded.counts[DisplayStatus.PAUSED])
                assertEquals(1, loaded.counts[DisplayStatus.MAINTENANCE])
                assertEquals(listOf("critical", "env", "team"), loaded.tags.map { it.name })
            }
        }

    @Test
    fun `search, status and tag filters narrow the rows`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)

            vm.onQueryChange("node")
            advanceUntilIdle()
            assertEquals(
                listOf("Web cluster", "Node A", "Node B"),
                vm.state.value.rows
                    .map { it.overview.monitor.name },
            )

            vm.onQueryChange("")
            vm.toggleStatus(DisplayStatus.DOWN)
            advanceUntilIdle()
            assertEquals(
                listOf("Legacy shop"),
                vm.state.value.rows
                    .map { it.overview.monitor.name },
            )

            vm.toggleStatus(DisplayStatus.DOWN)
            vm.toggleTag(SampleData.tagTeam.id)
            advanceUntilIdle()
            assertEquals(
                listOf("Public API", "Login service"),
                vm.state.value.rows
                    .map { it.overview.monitor.name },
            )

            vm.clearFilters()
            advanceUntilIdle()
            assertFalse(vm.state.value.filter.isFiltering)
            assertEquals(SampleData.monitors(now).size, vm.state.value.rows.size)
        }

    @Test
    fun `problems first puts the down monitor on top`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            vm.toggleProblemsFirst()
            advanceUntilIdle()
            val first =
                vm.state.value.rows
                    .first()
            assertEquals(DisplayStatus.DOWN, first.worstStatus)
            assertEquals("Legacy shop", first.overview.monitor.name)
        }

    @Test
    fun `collapsing a group hides its children`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            vm.toggleGroup(11)
            advanceUntilIdle()
            val names =
                vm.state.value.rows
                    .map { it.overview.monitor.name }
            assertTrue("Web cluster" in names)
            assertFalse("Node A" in names)
        }

    @Test
    fun `live updates from the repository are reflected`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            monitors.monitors.value = monitors.monitors.value.drop(1)
            advanceUntilIdle()
            assertEquals(SampleData.monitors(now).size - 1, vm.state.value.rows.size)
        }

    @Test
    fun `connection state is exposed`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            session.connectionState.value = ConnectionState.LoginRequired
            advanceUntilIdle()
            assertEquals(ConnectionState.LoginRequired, vm.state.value.connection)
        }

    @Test
    fun `refresh calls syncOnce and toggles refreshing`() =
        runTest(mainDispatcher.testDispatcher) {
            session.delayMillis = 100
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            vm.refresh()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, session.syncCount)
            assertFalse(vm.state.value.refreshing)
            assertNull(vm.state.value.refreshError)
        }

    @Test
    fun `refresh failure is shown once`() =
        runTest(mainDispatcher.testDispatcher) {
            session.syncError = KumaException(KumaError(KumaError.Kind.NETWORK))
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.refreshError
                    ?.kind,
            )
            vm.dismissRefreshError()
            advanceUntilIdle()
            assertNull(vm.state.value.refreshError)
        }

    @Test
    fun `refresh returning false reports unauthorized`() =
        runTest(mainDispatcher.testDispatcher) {
            session.syncResult = false
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.UNAUTHORIZED,
                vm.state.value.refreshError
                    ?.kind,
            )
        }

    @Test
    fun `checks for app updates and hides a dismissed one`() =
        runTest(mainDispatcher.testDispatcher) {
            updates.update.value = AppUpdate("9.0.0", "https://github.com/kirkanos/uptime-kuma-android/releases")
            val vm = DashboardViewModel(monitors, session, updates)
            collect(vm)
            assertEquals(1, updates.checks)
            assertEquals(
                "9.0.0",
                vm.state.value.appUpdate
                    ?.version,
            )
            vm.dismissAppUpdate("9.0.0")
            advanceUntilIdle()
            assertNull(vm.state.value.appUpdate)
        }
}
