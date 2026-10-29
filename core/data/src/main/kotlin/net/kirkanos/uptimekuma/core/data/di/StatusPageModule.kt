package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.statuspage.HttpGetter
import net.kirkanos.uptimekuma.core.data.statuspage.KumaStatusPageRepository
import net.kirkanos.uptimekuma.core.data.statuspage.UrlConnectionHttpGetter
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StatusPageModule {
    @Binds
    abstract fun bindStatusPageRepository(impl: KumaStatusPageRepository): StatusPageRepository

    @Binds
    abstract fun bindHttpGetter(impl: UrlConnectionHttpGetter): HttpGetter
}
