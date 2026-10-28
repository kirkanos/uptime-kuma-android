package net.kirkanos.uptimekuma.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.kirkanos.uptimekuma.core.data.LogoutHook
import net.kirkanos.uptimekuma.core.data.cache.KumaCache
import net.kirkanos.uptimekuma.core.data.cache.RoomKumaCache
import net.kirkanos.uptimekuma.core.data.config.DataStoreServerConfigStore
import net.kirkanos.uptimekuma.core.data.config.ServerConfigStore
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaApiFactory
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.data.db.KumaDatabase
import net.kirkanos.uptimekuma.core.data.repository.ConnectionServerInfoRepository
import net.kirkanos.uptimekuma.core.data.repository.DefaultSessionRepository
import net.kirkanos.uptimekuma.core.data.repository.RoomKumaNotificationRepository
import net.kirkanos.uptimekuma.core.data.repository.RoomMonitorRepository
import net.kirkanos.uptimekuma.core.data.secret.KeystoreSecretStore
import net.kirkanos.uptimekuma.core.data.secret.SecretStore
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.ServerInfoRepository
import net.kirkanos.uptimekuma.core.domain.SessionRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindServerInfoRepository(impl: ConnectionServerInfoRepository): ServerInfoRepository

    @Binds
    abstract fun bindSessionRepository(impl: DefaultSessionRepository): SessionRepository

    @Binds
    abstract fun bindMonitorRepository(impl: RoomMonitorRepository): MonitorRepository

    @Binds
    abstract fun bindNotificationRepository(impl: RoomKumaNotificationRepository): KumaNotificationRepository

    @Binds
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun bindServerConfigStore(impl: DataStoreServerConfigStore): ServerConfigStore

    @Binds
    abstract fun bindKumaCache(impl: RoomKumaCache): KumaCache

    @Binds
    abstract fun bindKumaApiFactory(impl: SocketKumaApiFactory): KumaApiFactory

    /** Other modules contribute with `@Binds @IntoSet`; empty by default. */
    @Multibinds
    abstract fun logoutHooks(): Set<LogoutHook>

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(
            @ApplicationContext context: Context,
        ): KumaDatabase =
            Room
                .databaseBuilder(context, KumaDatabase::class.java, KumaDatabase.NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        @Provides
        @Singleton
        @KumaSettings
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("kuma_settings") }

        @Provides
        @DataDispatcher
        @Suppress("InjectDispatcher")
        fun provideDataDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        @DataScope
        fun provideDataScope(
            @DataDispatcher dispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

        @Provides
        fun provideConnectionTimings(): ConnectionTimings = ConnectionTimings()
    }
}
