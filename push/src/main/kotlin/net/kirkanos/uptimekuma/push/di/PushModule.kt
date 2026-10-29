package net.kirkanos.uptimekuma.push.di

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.BindsOptionalOf
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
import net.kirkanos.uptimekuma.core.domain.AlertHistoryRepository
import net.kirkanos.uptimekuma.core.domain.AlertPreferencesRepository
import net.kirkanos.uptimekuma.core.domain.MonitorStateListener
import net.kirkanos.uptimekuma.core.domain.PushRepository
import net.kirkanos.uptimekuma.push.AlertPresenter
import net.kirkanos.uptimekuma.push.alerts.AlertHistoryStore
import net.kirkanos.uptimekuma.push.alerts.DataStoreAlertPreferencesRepository
import net.kirkanos.uptimekuma.push.data.DataStorePushRegistrationStore
import net.kirkanos.uptimekuma.push.data.DeviceNameProvider
import net.kirkanos.uptimekuma.push.data.DistributorGateway
import net.kirkanos.uptimekuma.push.data.PushRegistrationStore
import net.kirkanos.uptimekuma.push.data.PushRepositoryImpl
import net.kirkanos.uptimekuma.push.data.PushSecretStore
import net.kirkanos.uptimekuma.push.data.PushTokenStore
import net.kirkanos.uptimekuma.push.data.UnifiedPushDistributorGateway
import net.kirkanos.uptimekuma.push.firebase.FirebaseSettingsStore
import net.kirkanos.uptimekuma.push.firebase.FirebaseTransport
import net.kirkanos.uptimekuma.push.firebase.SharedPrefsFirebaseSettingsStore
import net.kirkanos.uptimekuma.push.notify.AlertNotifier
import net.kirkanos.uptimekuma.push.work.PushWorkScheduler
import net.kirkanos.uptimekuma.push.work.WorkManagerPushScheduler
import javax.inject.Qualifier
import javax.inject.Singleton

/** IO dispatcher used inside :push (local qualifier; the app may provide a shared one later). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PushIoDispatcher

/** Process-wide scope for short push follow-up work started from `KumaPushService`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PushScope

/** The [PushTokenStore] holding the FCM relay token (the unqualified one holds the ntfy token). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RelayTokenStore

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PushBindingsModule {
    @Binds
    abstract fun bindPushRepository(impl: PushRepositoryImpl): PushRepository

    @Binds
    abstract fun bindDistributorGateway(impl: UnifiedPushDistributorGateway): DistributorGateway

    @Binds
    abstract fun bindRegistrationStore(impl: DataStorePushRegistrationStore): PushRegistrationStore

    @Binds
    abstract fun bindFirebaseSettingsStore(impl: SharedPrefsFirebaseSettingsStore): FirebaseSettingsStore

    @Binds
    abstract fun bindAlertPresenter(impl: AlertNotifier): AlertPresenter

    /** Bound by `:push-fcm` (flavor `full`); absent in `foss`, where only UnifiedPush exists. */
    @BindsOptionalOf
    abstract fun optionalFirebaseTransport(): FirebaseTransport

    @Binds
    abstract fun bindWorkScheduler(impl: WorkManagerPushScheduler): PushWorkScheduler

    @Binds
    abstract fun bindAlertPreferencesRepository(impl: DataStoreAlertPreferencesRepository): AlertPreferencesRepository

    @Binds
    abstract fun bindAlertHistoryRepository(impl: AlertHistoryStore): AlertHistoryRepository

    /** Widget / tile contribute with `@Binds @IntoSet`; empty when none is installed. */
    @Multibinds
    abstract fun monitorStateListeners(): Set<MonitorStateListener>
}

@Module
@InstallIn(SingletonComponent::class)
internal object PushProvidesModule {
    @Provides
    @PushIoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @PushScope
    fun providePushScope(
        @PushIoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideNtfyTokenStore(
        @ApplicationContext context: Context,
    ): PushTokenStore = PushSecretStore(context, PushSecretStore.NTFY_TOKEN_KEY)

    @Provides
    @Singleton
    @RelayTokenStore
    fun provideRelayTokenStore(
        @ApplicationContext context: Context,
    ): PushTokenStore = PushSecretStore(context, PushSecretStore.RELAY_TOKEN_KEY)

    @Provides
    fun provideDeviceNameProvider(): DeviceNameProvider =
        DeviceNameProvider {
            Build.MODEL
                ?.takeIf { it.isNotBlank() }
                ?: Build.DEVICE.orEmpty()
        }
}
