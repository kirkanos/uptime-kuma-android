package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.maintenance.ConnectionMaintenanceBackend
import net.kirkanos.uptimekuma.core.data.maintenance.KumaMaintenanceRepository
import net.kirkanos.uptimekuma.core.data.maintenance.MaintenanceBackend
import net.kirkanos.uptimekuma.core.domain.MaintenanceRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MaintenanceModule {
    @Binds
    abstract fun bindMaintenanceRepository(impl: KumaMaintenanceRepository): MaintenanceRepository

    @Binds
    abstract fun bindMaintenanceBackend(impl: ConnectionMaintenanceBackend): MaintenanceBackend
}
