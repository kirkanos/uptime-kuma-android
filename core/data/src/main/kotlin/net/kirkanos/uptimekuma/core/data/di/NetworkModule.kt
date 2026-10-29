package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.connection.ConnectivityNetworkMonitor
import net.kirkanos.uptimekuma.core.data.connection.NetworkMonitor

/** Default-network changes for the connection manager's fast reconnect. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds
    abstract fun bindNetworkMonitor(impl: ConnectivityNetworkMonitor): NetworkMonitor
}
