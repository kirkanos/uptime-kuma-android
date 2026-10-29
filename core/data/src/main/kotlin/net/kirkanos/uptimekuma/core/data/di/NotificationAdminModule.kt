package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.notification.ConnectionNotificationAdminBackend
import net.kirkanos.uptimekuma.core.data.notification.KumaNotificationAdminRepository
import net.kirkanos.uptimekuma.core.data.notification.NotificationAdminBackend
import net.kirkanos.uptimekuma.core.domain.NotificationAdminRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotificationAdminModule {
    @Binds
    abstract fun bindNotificationAdminRepository(impl: KumaNotificationAdminRepository): NotificationAdminRepository

    @Binds
    abstract fun bindNotificationAdminBackend(impl: ConnectionNotificationAdminBackend): NotificationAdminBackend
}
