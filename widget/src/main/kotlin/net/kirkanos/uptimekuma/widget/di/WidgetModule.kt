package net.kirkanos.uptimekuma.widget.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.kirkanos.uptimekuma.core.domain.MonitorStateListener
import net.kirkanos.uptimekuma.widget.AndroidWidgetSurfaces
import net.kirkanos.uptimekuma.widget.state.WidgetMonitorStateListener
import net.kirkanos.uptimekuma.widget.state.WidgetStateHolder
import net.kirkanos.uptimekuma.widget.state.WidgetSurfaces
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dispatcher for widget state work (mapping, Glance updates). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WidgetDispatcher

/** Process-wide scope of the widget updater. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WidgetScope

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetBindingsModule {
    @Binds
    abstract fun bindSurfaces(impl: AndroidWidgetSurfaces): WidgetSurfaces

    @Binds
    @IntoSet
    abstract fun bindMonitorStateListener(impl: WidgetMonitorStateListener): MonitorStateListener
}

@Module
@InstallIn(SingletonComponent::class)
internal object WidgetProvidesModule {
    @Provides
    @WidgetDispatcher
    fun provideDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @WidgetScope
    fun provideScope(
        @WidgetDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

/** Access from Glance (not injectable) to the Hilt graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateHolder(): WidgetStateHolder
}
