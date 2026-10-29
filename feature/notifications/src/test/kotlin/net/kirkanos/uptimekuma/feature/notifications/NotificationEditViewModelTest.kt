package net.kirkanos.uptimekuma.feature.notifications

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.testing.FakeNotificationAdminRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.notifications.edit.Busy
import net.kirkanos.uptimekuma.feature.notifications.edit.NotificationEditViewModel
import net.kirkanos.uptimekuma.feature.notifications.edit.TestResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationEditViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    /** Records `applyExisting`, which the shared fake does not. */
    private class RecordingRepository(
        initial: List<KumaNotification>,
    ) : KumaNotificationRepository {
        val notifications = MutableStateFlow(initial)
        val saved = mutableListOf<Pair<KumaNotification, Boolean>>()
        val deleted = mutableListOf<Long>()
        var error: KumaException? = null

        override fun observeNotifications(): Flow<List<KumaNotification>> = notifications

        override suspend fun save(
            notification: KumaNotification,
            applyExisting: Boolean,
        ): Long {
            error?.let { throw it }
            saved += notification to applyExisting
            return notification.id ?: NEW_ID
        }

        override suspend fun delete(id: Long) {
            error?.let { throw it }
            deleted += id
        }

        override suspend fun test(notification: KumaNotification) = Unit
    }

    private val repository = RecordingRepository(PreviewData.all)
    private val admin = FakeNotificationAdminRepository()

    private fun TestScope.viewModel(
        id: Long = NotificationEditRoute.NEW,
        type: String = "",
    ): NotificationEditViewModel {
        val handle = SavedStateHandle(mapOf(NotificationEditRoute.ARG_ID to id, NotificationEditRoute.ARG_TYPE to type))
        val vm = NotificationEditViewModel(handle, repository, admin)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `new notification starts from the spec defaults`() =
        runTest(mainDispatcher.testDispatcher) {
            val state = viewModel(type = "ntfy").state.value
            assertFalse(state.loading)
            assertTrue(state.isNew)
            val draft = requireNotNull(state.draft)
            assertEquals("My Ntfy Alert (1)", draft.name)
            assertEquals("https://ntfy.sh", draft.values["ntfyserverurl"])
            assertEquals(listOf("ntfytopic"), state.issues.map { it.field })
            assertNull(state.issueFor("ntfytopic"), "errors are hidden until the first attempt")
        }

    @Test
    fun `save validates first, then creates with applyExisting`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(type = "ntfy")
            vm.save()
            advanceUntilIdle()
            assertTrue(repository.saved.isEmpty())
            assertEquals(
                "ntfytopic",
                vm.state.value
                    .issueFor("ntfytopic")
                    ?.field,
            )

            vm.setValue("ntfytopic", "upP3")
            vm.setValue("ntfyPriority", "4")
            vm.setName("[P3-NOTIF] ntfy")
            vm.setDefault(true)
            vm.setApplyExisting(true)
            vm.save()
            advanceUntilIdle()
            val (saved, applyExisting) = repository.saved.single()
            assertTrue(applyExisting)
            assertNull(saved.id)
            assertEquals("[P3-NOTIF] ntfy", saved.name)
            assertTrue(saved.isDefault)
            assertEquals(4L, saved.fields["ntfyPriority"])
            assertEquals("upP3", saved.fields["ntfytopic"])
            assertTrue(vm.state.value.done)
        }

    @Test
    fun `editing keeps unknown fields and never applies to existing monitors`() =
        runTest(mainDispatcher.testDispatcher) {
            repository.notifications.value =
                listOf(PreviewData.ntfy.copy(fields = PreviewData.ntfy.fields + ("legacyField" to "keep me")))
            val vm = viewModel(id = 2)
            assertFalse(vm.state.value.isNew)
            vm.setApplyExisting(true)
            vm.setValue("ntfytopic", "other")
            vm.save()
            advanceUntilIdle()
            val (saved, applyExisting) = repository.saved.single()
            assertFalse(applyExisting)
            assertEquals(2L, saved.id)
            assertEquals("keep me", saved.fields["legacyField"])
            assertEquals("other", saved.fields["ntfytopic"])
            assertEquals(false, saved.fields["ntfyCall"])
        }

    @Test
    fun `test sends the unsaved config and shows Kuma's answer`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 1)
            vm.setValue("webhookURL", "http://localhost:9")
            vm.test()
            assertEquals(Busy.TESTING, vm.state.value.busy)
            advanceUntilIdle()
            assertEquals("http://localhost:9", admin.tested.single().fields["webhookURL"])
            assertEquals(TestResult.Success("Sent Successfully."), vm.state.value.testResult)
            assertTrue(repository.saved.isEmpty(), "testing does not save")
            vm.consumeMessages()
            assertNull(vm.state.value.testResult)

            admin.testError =
                KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "connect ECONNREFUSED 127.0.0.1:9"))
            vm.test()
            advanceUntilIdle()
            val failure = vm.state.value.testResult as TestResult.Failure
            assertEquals("connect ECONNREFUSED 127.0.0.1:9", failure.error.message)
        }

    @Test
    fun `invalid config is not tested`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(type = "smtp")
            vm.test()
            advanceUntilIdle()
            assertTrue(admin.tested.isEmpty())
            assertTrue(vm.state.value.showErrors)
            assertEquals(
                setOf("smtpHost", "smtpPort", "smtpFrom"),
                vm.state.value.issues
                    .map { it.field }
                    .toSet(),
            )
        }

    @Test
    fun `device webhook is flagged, delete removes it`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 3)
            assertTrue(vm.state.value.isThisDevice)
            vm.delete()
            advanceUntilIdle()
            assertEquals(listOf(3L), repository.deleted)
            assertTrue(vm.state.value.done)
        }

    @Test
    fun `save failure keeps the screen open with the error`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 2)
            repository.error = KumaException(KumaError(KumaError.Kind.NETWORK))
            vm.save()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.actionError
                    ?.kind,
            )
            assertFalse(vm.state.value.done)
            vm.consumeMessages()
            assertNull(vm.state.value.actionError)
        }

    @Test
    fun `headers toggle hides the field and clears it on save`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel(id = 3)
            assertEquals(
                true,
                vm.state.value.draft
                    ?.toggles
                    ?.get("showAdditionalHeadersField"),
            )
            vm.setToggle("showAdditionalHeadersField", false)
            vm.save()
            advanceUntilIdle()
            val saved = repository.saved.single().first
            assertTrue("webhookAdditionalHeaders" in saved.fields)
            assertNull(saved.fields["webhookAdditionalHeaders"])
        }

    @Test
    fun `apprise availability is checked for apprise`() =
        runTest(mainDispatcher.testDispatcher) {
            admin.apprise = false
            assertEquals(false, viewModel(type = "apprise").state.value.appriseAvailable)
            assertNull(viewModel(type = "telegram").state.value.appriseAvailable)
        }

    @Test
    fun `unknown id ends in not found after waiting, unknown provider type keeps the config`() =
        runTest(mainDispatcher.testDispatcher) {
            val handle = SavedStateHandle(mapOf(NotificationEditRoute.ARG_ID to 99L))
            val vm = NotificationEditViewModel(handle, repository, admin)
            advanceTimeBy(1_000)
            assertTrue(vm.state.value.loading)
            advanceUntilIdle()
            assertTrue(vm.state.value.notFound)

            val unknown = viewModel(id = 5)
            assertNull(unknown.state.value.provider)
            unknown.save()
            advanceUntilIdle()
            assertEquals(PreviewData.unknown, repository.saved.single().first)

            assertTrue(viewModel(type = "doesNotExist").state.value.notFound)
        }

    private companion object {
        const val NEW_ID = 42L
    }
}
