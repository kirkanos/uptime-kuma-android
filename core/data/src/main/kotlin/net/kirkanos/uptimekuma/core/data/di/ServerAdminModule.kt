package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.admin.AdminBackend
import net.kirkanos.uptimekuma.core.data.admin.ConnectionAdminBackend
import net.kirkanos.uptimekuma.core.data.admin.KumaAccountRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaApiKeyRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaCloudflaredRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaDockerHostRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaProxyRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaRemoteBrowserRepository
import net.kirkanos.uptimekuma.core.data.admin.KumaServerSettingsRepository
import net.kirkanos.uptimekuma.core.domain.AccountRepository
import net.kirkanos.uptimekuma.core.domain.ApiKeyRepository
import net.kirkanos.uptimekuma.core.domain.CloudflaredRepository
import net.kirkanos.uptimekuma.core.domain.DockerHostRepository
import net.kirkanos.uptimekuma.core.domain.ProxyRepository
import net.kirkanos.uptimekuma.core.domain.RemoteBrowserRepository
import net.kirkanos.uptimekuma.core.domain.ServerSettingsRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ServerAdminModule {
    @Binds
    abstract fun bindAdminBackend(impl: ConnectionAdminBackend): AdminBackend

    @Binds
    abstract fun bindServerSettingsRepository(impl: KumaServerSettingsRepository): ServerSettingsRepository

    @Binds
    abstract fun bindAccountRepository(impl: KumaAccountRepository): AccountRepository

    @Binds
    abstract fun bindApiKeyRepository(impl: KumaApiKeyRepository): ApiKeyRepository

    @Binds
    abstract fun bindProxyRepository(impl: KumaProxyRepository): ProxyRepository

    @Binds
    abstract fun bindDockerHostRepository(impl: KumaDockerHostRepository): DockerHostRepository

    @Binds
    abstract fun bindRemoteBrowserRepository(impl: KumaRemoteBrowserRepository): RemoteBrowserRepository

    @Binds
    abstract fun bindCloudflaredRepository(impl: KumaCloudflaredRepository): CloudflaredRepository
}
