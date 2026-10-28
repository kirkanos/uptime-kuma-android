package net.kirkanos.uptimekuma.feature.monitors

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.ServerInfo
import net.kirkanos.uptimekuma.core.testing.FakeServerInfoRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorsViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @Test
    fun `exposes server version from repository`() =
        runTest {
            val repository = FakeServerInfoRepository()
            val viewModel = MonitorsViewModel(repository)

            viewModel.serverVersion.test {
                assertNull(awaitItem())
                repository.emit(ServerInfo(version = "2.0.1"))
                assertEquals("2.0.1", awaitItem())
            }
        }
}
