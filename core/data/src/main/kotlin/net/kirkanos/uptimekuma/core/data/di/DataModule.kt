package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.repository.InMemoryServerInfoRepository
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindServerInfoRepository(impl: InMemoryServerInfoRepository): ServerInfoRepository
}
