package net.kirkanos.uptimekuma.feature.onboarding

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.model.ServerConfig
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val session = FakeSessionRepository()

    private fun TestScope.viewModel(): LoginViewModel = LoginViewModel(session).also { advanceUntilIdle() }

    private fun LoginViewModel.enterCredentials() {
        onServerUrlChange("uptime.example.org")
        submitServerUrl()
        onUsernameChange("admin")
        onPasswordChange("secret")
    }

    @Test
    fun `invalid url stays on server step with error`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.submitServerUrl()
            assertEquals(LoginStep.SERVER, vm.state.value.step)
            assertEquals(ServerUrl.Reason.EMPTY, vm.state.value.urlError)

            vm.onServerUrlChange("x")
            assertNull(vm.state.value.urlError)
        }

    @Test
    fun `valid url moves to credentials with normalised url`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.onServerUrlChange("http://kuma.lan:3001/")
            vm.submitServerUrl()
            val state = vm.state.value
            assertEquals(LoginStep.CREDENTIALS, state.step)
            assertEquals("http://kuma.lan:3001", state.baseUrl)
            assertTrue(state.insecure)
        }

    @Test
    fun `successful login shows progress and finishes`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.enterCredentials()
            vm.state.test {
                assertFalse(awaitItem().busy)
                vm.submitCredentials()
                assertTrue(awaitItem().busy)
                val done = awaitItem()
                assertFalse(done.busy)
                assertEquals(LoginStep.DONE, done.step)
                assertEquals("", done.password)
            }
            assertEquals(
                FakeSessionRepository.LoginCall("https://uptime.example.org", "admin", "secret", null),
                session.loginCalls.single(),
            )
        }

    @Test
    fun `two factor flow sends totp`() =
        runTest(mainDispatcher.testDispatcher) {
            session.loginHandler =
                { call -> if (call.totp == null) LoginResult.TwoFactorRequired else LoginResult.Success }
            val vm = viewModel()
            vm.enterCredentials()
            vm.submitCredentials()
            advanceUntilIdle()
            assertEquals(LoginStep.TWO_FACTOR, vm.state.value.step)
            assertFalse(vm.state.value.canSubmitTotp)

            vm.onTotpChange("12a34 56789")
            assertEquals("123456", vm.state.value.totp)
            vm.submitTotp()
            advanceUntilIdle()
            assertEquals(LoginStep.DONE, vm.state.value.step)
            assertEquals("123456", session.loginCalls.last().totp)
        }

    @Test
    fun `wrong totp shows invalid credentials and stays on 2fa step`() =
        runTest(mainDispatcher.testDispatcher) {
            session.loginHandler = { LoginResult.TwoFactorRequired }
            val vm = viewModel()
            vm.enterCredentials()
            vm.submitCredentials()
            advanceUntilIdle()
            vm.onTotpChange("000000")
            vm.submitTotp()
            advanceUntilIdle()
            assertEquals(LoginStep.TWO_FACTOR, vm.state.value.step)
            assertEquals(
                KumaError.Kind.INVALID_CREDENTIALS,
                vm.state.value.error
                    ?.kind,
            )
            assertEquals("", vm.state.value.totp)
        }

    @Test
    fun `setup required failure is surfaced`() =
        runTest(mainDispatcher.testDispatcher) {
            session.loginHandler = { LoginResult.Failure(KumaError(KumaError.Kind.SETUP_REQUIRED)) }
            val vm = viewModel()
            vm.enterCredentials()
            vm.submitCredentials()
            advanceUntilIdle()
            assertEquals(LoginStep.CREDENTIALS, vm.state.value.step)
            assertEquals(
                KumaError.Kind.SETUP_REQUIRED,
                vm.state.value.error
                    ?.kind,
            )
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `exceptions are mapped to errors`() =
        runTest(mainDispatcher.testDispatcher) {
            session.loginHandler = { throw KumaException(KumaError(KumaError.Kind.NETWORK)) }
            val vm = viewModel()
            vm.enterCredentials()
            vm.submitCredentials()
            advanceUntilIdle()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.error
                    ?.kind,
            )

            vm.onPasswordChange("other")
            assertNull(vm.state.value.error)
        }

    @Test
    fun `cannot submit without credentials`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.onServerUrlChange("example.org")
            vm.submitServerUrl()
            vm.submitCredentials()
            advanceUntilIdle()
            assertTrue(session.loginCalls.isEmpty())
        }

    @Test
    fun `back navigates between steps`() =
        runTest(mainDispatcher.testDispatcher) {
            session.loginHandler = { LoginResult.TwoFactorRequired }
            val vm = viewModel()
            assertFalse(vm.back())
            vm.enterCredentials()
            vm.submitCredentials()
            advanceUntilIdle()
            assertTrue(vm.back())
            assertEquals(LoginStep.CREDENTIALS, vm.state.value.step)
            assertTrue(vm.back())
            assertEquals(LoginStep.SERVER, vm.state.value.step)
        }

    @Test
    fun `re-login prefills configured server and skips url step`() =
        runTest(mainDispatcher.testDispatcher) {
            session.serverConfig.value = ServerConfig(baseUrl = "https://kuma.example.org", username = "ops")
            val vm = viewModel()
            val state = vm.state.value
            assertEquals(LoginStep.CREDENTIALS, state.step)
            assertTrue(state.reLogin)
            assertEquals("ops", state.username)
            assertEquals("https://kuma.example.org", state.baseUrl)
            assertFalse(vm.back())
        }
}
