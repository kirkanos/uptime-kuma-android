package net.kirkanos.uptimekuma.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.LoginResult
import net.kirkanos.uptimekuma.core.testing.FakeKumaNotificationRepository
import net.kirkanos.uptimekuma.core.testing.FakeMonitorRepository
import net.kirkanos.uptimekuma.core.testing.FakePushRepository
import net.kirkanos.uptimekuma.core.testing.FakeSessionRepository
import net.kirkanos.uptimekuma.core.testing.SampleData
import javax.inject.Singleton

// ================================================================================================
// TODO(merge): remove when :core:data/:push provide bindings
//
// Binds the four repositories of the contract to the in-memory fakes of :core:testing so the app
// builds, passes Hilt graph validation and launches with sample data while :core:data and :push are
// developed in parallel. Delete this file and the `implementation(projects.core.testing)` line in
// app/build.gradle.kts once the real bindings exist (Hilt would otherwise report duplicate bindings).
//
// Demo login behaviour: username "2fa" asks for a TOTP code, password "wrong" fails, a server URL
// containing "setup" reports SETUP_REQUIRED; everything else succeeds.
// ================================================================================================
@Module
@InstallIn(SingletonComponent::class)
object TemporaryBindingsModule {
    private const val DEMO_LATENCY_MS = 600L

    @Provides
    @Singleton
    fun provideSessionRepository(): SessionRepository =
        FakeSessionRepository(
            initialConfig = SampleData.serverConfig,
            initialConnection = ConnectionState.Connected(SampleData.serverInfo),
        ).apply {
            delayMillis = DEMO_LATENCY_MS
            loginHandler = { call ->
                when {
                    "setup" in call.baseUrl -> LoginResult.Failure(KumaError(KumaError.Kind.SETUP_REQUIRED))
                    call.password == "wrong" -> LoginResult.Failure(KumaError(KumaError.Kind.INVALID_CREDENTIALS))
                    call.username == "2fa" && call.totp == null -> LoginResult.TwoFactorRequired
                    else -> LoginResult.Success
                }
            }
        }

    @Provides
    @Singleton
    fun provideMonitorRepository(): MonitorRepository = FakeMonitorRepository.withSampleData()

    @Provides
    @Singleton
    fun provideKumaNotificationRepository(): KumaNotificationRepository = FakeKumaNotificationRepository()

    @Provides
    @Singleton
    fun providePushRepository(): PushRepository = FakePushRepository()
}
