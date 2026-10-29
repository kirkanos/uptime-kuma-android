package net.kirkanos.uptimekuma.feature.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.testing.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.core.testing.FakeNotificationAdminRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.notifications.list.NotificationListViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationListViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repository = FakeKumaNotificationRepository(PreviewData.all)
    private val admin = FakeNotificationAdminRepository()

    private fun TestScope.viewModel(): NotificationListViewModel {
        val vm = NotificationListViewModel(repository, admin)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `rows are sorted by name with provider and device marker`() =
        runTest(mainDispatcher.testDispatcher) {
            val rows = requireNotNull(viewModel().state.value.rows)
            assertEquals(
                listOf("Dev ntfy", "Dev webhook", "From the future", "Ops mail", "Pixel 9 (UnifiedPush)"),
                rows.map { it.notification.name },
            )
            val device = rows.last()
            assertTrue(device.isThisDevice)
            assertEquals("Webhook", device.provider?.label)
            assertEquals(listOf(false, false, false, false, true), rows.map { it.isThisDevice })
            assertNull(rows.first { it.notification.type == "hologram" }.provider)
            assertEquals("Email (SMTP)", rows.first { it.notification.type == "smtp" }.provider?.label)
        }

    @Test
    fun `connects on start and follows the cache`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            assertEquals(1, admin.appriseChecks)
            assertFalse(vm.state.value.refreshing)
            repository.notifications.value = emptyList()
            advanceUntilIdle()
            assertTrue(vm.state.value.isEmpty)
        }

    @Test
    fun `connection failure is reported but the cached list stays`() =
        runTest(mainDispatcher.testDispatcher) {
            admin.connectError = KumaException(KumaError(KumaError.Kind.NETWORK))
            val vm = viewModel()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.connectError
                    ?.kind,
            )
            assertEquals(
                5,
                vm.state.value.rows
                    ?.size,
            )
            admin.connectError = null
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.connectError)
            assertEquals(2, admin.appriseChecks)
        }
}
