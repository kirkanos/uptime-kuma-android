package net.kirkanos.uptimekuma.feature.monitors.tags

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Tag
import net.kirkanos.uptimekuma.core.testing.FakeTagRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class TagManagerViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val repo = FakeTagRepository()

    private fun TestScope.viewModel(): TagManagerViewModel {
        val vm = TagManagerViewModel(repo)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `lists tags after refresh`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            assertEquals(1, repo.refreshCount)
            assertFalse(vm.state.value.refreshing)
            assertEquals(
                listOf("env", "team", "critical"),
                vm.state.value.tags
                    .map { it.name },
            )
        }

    @Test
    fun `create, update and delete`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.create(" prod ", "#2563EB")
            advanceUntilIdle()
            val created =
                vm.state.value.tags
                    .last()
            assertEquals(Tag(4, "prod", "#2563EB"), created)
            vm.update(created.copy(name = "production ", color = "#DC2626"))
            advanceUntilIdle()
            assertEquals(
                Tag(4, "production", "#DC2626"),
                vm.state.value.tags
                    .last(),
            )
            vm.delete(4)
            advanceUntilIdle()
            assertEquals(3, vm.state.value.tags.size)
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `errors are reported once`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            repo.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "tagNotFound"))
            vm.delete(1)
            advanceUntilIdle()
            assertEquals(
                "tagNotFound",
                vm.state.value.error
                    ?.message,
            )
            vm.dismissError()
            advanceUntilIdle()
            assertNull(vm.state.value.error)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.SERVER_REJECTED,
                vm.state.value.error
                    ?.kind,
            )
        }
}
