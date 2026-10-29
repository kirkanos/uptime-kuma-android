package net.kirkanos.uptimekuma.feature.settings.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.kirkanos.uptimekuma.core.domain.PasswordStrength
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.ServerSettings
import net.kirkanos.uptimekuma.core.testing.FakeAccountRepository
import net.kirkanos.uptimekuma.core.testing.FakeServerSettingsRepository
import net.kirkanos.uptimekuma.core.testing.MainDispatcherExtension
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminDone
import net.kirkanos.uptimekuma.feature.settings.server.common.AdminMessage
import net.kirkanos.uptimekuma.feature.settings.server.security.PasswordForm
import net.kirkanos.uptimekuma.feature.settings.server.security.SecurityViewModel
import net.kirkanos.uptimekuma.feature.settings.server.security.TwoFactorDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val account = FakeAccountRepository(password = "old-Passw0rd!")
    private val settings = FakeServerSettingsRepository(ServerSettings())

    private fun TestScope.viewModel(): SecurityViewModel =
        SecurityViewModel(account, settings).also { advanceUntilIdle() }

    @Test
    fun `loads 2FA status and the auth setting`() =
        runTest(mainDispatcher.testDispatcher) {
            val state = viewModel().state.value
            assertEquals(false, state.twoFactorEnabled)
            assertEquals(false, state.authDisabled)
        }

    @Test
    fun `password form validation`() {
        assertFalse(PasswordForm(current = "x", new = "abc123", repeat = "abc124").canSubmit)
        assertTrue(PasswordForm(current = "x", new = "abc123", repeat = "abc124").mismatch)
        assertFalse(PasswordForm(current = "x", new = "abcdefgh", repeat = "abcdefgh").canSubmit)
        assertEquals(PasswordStrength.TOO_WEAK, PasswordForm(new = "abcdefgh").strength)
        assertFalse(PasswordForm(current = "", new = "abc123", repeat = "abc123").canSubmit)
        assertTrue(PasswordForm(current = "x", new = "abc123", repeat = "abc123").canSubmit)
        assertNull(PasswordForm().strength)
    }

    @Test
    fun `changes the password and clears the form`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.updatePassword {
                PasswordForm(
                    current = "old-Passw0rd!",
                    new = "New-Passw0rd!",
                    repeat = "New-Passw0rd!",
                )
            }
            vm.changePassword()
            assertTrue(vm.state.value.password.busy)
            advanceUntilIdle()
            assertEquals("New-Passw0rd!", account.password)
            assertEquals(PasswordForm(), vm.state.value.password)
            assertEquals(AdminMessage.Done(AdminDone.PASSWORD_CHANGED), vm.state.value.message)
        }

    @Test
    fun `wrong current password keeps the form`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.updatePassword { PasswordForm(current = "nope", new = "New-Passw0rd!", repeat = "New-Passw0rd!") }
            vm.changePassword()
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals("nope", state.password.current)
            assertFalse(state.password.busy)
            assertEquals("Incorrect current password", (state.message as AdminMessage.Failed).error.message)
            assertEquals("old-Passw0rd!", account.password)
        }

    @Test
    fun `enable 2FA - password, secret, wrong code, right code`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.startEnableTwoFactor()
            vm.prepareTwoFactor("wrong")
            advanceUntilIdle()
            val failed = vm.state.value.twoFactorDialog as TwoFactorDialog.EnablePassword
            assertEquals("Incorrect current password", failed.error?.message)

            vm.prepareTwoFactor("old-Passw0rd!")
            advanceUntilIdle()
            val verify = vm.state.value.twoFactorDialog as TwoFactorDialog.EnableVerify
            assertEquals("JBSWY3DPEHPK3PXP", verify.setup.secret)

            vm.confirmEnableTwoFactor("000000")
            advanceUntilIdle()
            assertTrue((vm.state.value.twoFactorDialog as TwoFactorDialog.EnableVerify).invalidCode)
            assertFalse(account.twoFactor)

            vm.confirmEnableTwoFactor("123456")
            advanceUntilIdle()
            assertNull(vm.state.value.twoFactorDialog)
            assertTrue(account.twoFactor)
            assertEquals(true, vm.state.value.twoFactorEnabled)
            assertEquals(AdminMessage.Done(AdminDone.TWO_FACTOR_ENABLED), vm.state.value.message)
            assertEquals(listOf("verifyTwoFactorToken:123456", "enableTwoFactor"), account.calls.takeLast(2))
        }

    @Test
    fun `disable 2FA needs password and a valid code`() =
        runTest(mainDispatcher.testDispatcher) {
            account.twoFactor = true
            val vm = viewModel()
            assertEquals(true, vm.state.value.twoFactorEnabled)
            vm.startDisableTwoFactor()
            vm.confirmDisableTwoFactor("old-Passw0rd!", "999999")
            advanceUntilIdle()
            assertTrue((vm.state.value.twoFactorDialog as TwoFactorDialog.Disable).invalidCode)
            assertTrue(account.twoFactor)
            vm.confirmDisableTwoFactor("old-Passw0rd!", "123456")
            advanceUntilIdle()
            assertFalse(account.twoFactor)
            assertEquals(false, vm.state.value.twoFactorEnabled)
            assertNull(vm.state.value.twoFactorDialog)
        }

    @Test
    fun `dismissing the dialog`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.startEnableTwoFactor()
            vm.dismissTwoFactorDialog()
            assertNull(vm.state.value.twoFactorDialog)
        }

    @Test
    fun `disable auth sends the password, enabling does not`() =
        runTest(mainDispatcher.testDispatcher) {
            val vm = viewModel()
            vm.setAuthDisabled(true, "old-Passw0rd!")
            advanceUntilIdle()
            assertEquals(true, vm.state.value.authDisabled)
            assertEquals("old-Passw0rd!", settings.authDisabledPassword)
            assertEquals(AdminMessage.Done(AdminDone.AUTH_DISABLED), vm.state.value.message)
            vm.setAuthDisabled(false, "ignored")
            advanceUntilIdle()
            assertEquals(false, vm.state.value.authDisabled)
            assertNull(settings.authDisabledPassword)
        }

    @Test
    fun `status errors are shown`() =
        runTest(mainDispatcher.testDispatcher) {
            account.failWith = KumaError(KumaError.Kind.NETWORK)
            settings.failWith = KumaError(KumaError.Kind.TIMEOUT)
            val vm = viewModel()
            assertEquals(
                KumaError.Kind.NETWORK,
                vm.state.value.twoFactorError
                    ?.kind,
            )
            assertEquals(
                KumaError.Kind.TIMEOUT,
                vm.state.value.authError
                    ?.kind,
            )
            account.failWith = null
            settings.failWith = null
            vm.refresh()
            advanceUntilIdle()
            assertNull(vm.state.value.twoFactorError)
            assertEquals(false, vm.state.value.authDisabled)
        }
}
