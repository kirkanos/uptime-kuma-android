package net.kirkanos.uptimekuma.di

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import net.kirkanos.uptimekuma.core.data.LogoutHook
import net.kirkanos.uptimekuma.core.domain.PushRepository

/**
 * Connects :push to the logout flow of :core:data without a dependency between the two modules:
 * on logout this device's Kuma webhook notification is deleted and UnifiedPush is unregistered
 * while the session is still valid. [Lazy] breaks the injection cycle
 * KumaConnectionManager -> LogoutHook -> PushRepository -> KumaNotificationRepository
 * -> KumaConnectionManager.
 */
@Module
@InstallIn(SingletonComponent::class)
object PushLogoutModule {
    @Provides
    @IntoSet
    fun providePushLogoutHook(pushRepository: Lazy<PushRepository>): LogoutHook =
        LogoutHook { pushRepository.get().tearDown() }
}
