package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.update.GitHubAppUpdateRepository
import net.kirkanos.uptimekuma.core.domain.AppUpdateRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppUpdateModule {
    @Binds
    abstract fun bindAppUpdateRepository(impl: GitHubAppUpdateRepository): AppUpdateRepository
}
