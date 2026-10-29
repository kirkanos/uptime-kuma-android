package net.kirkanos.uptimekuma.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.core.data.editor.EditorRemote
import net.kirkanos.uptimekuma.core.data.editor.KumaMonitorEditorRepository
import net.kirkanos.uptimekuma.core.data.editor.KumaTagRepository
import net.kirkanos.uptimekuma.core.data.editor.ManagerEditorRemote
import net.kirkanos.uptimekuma.core.domain.MonitorEditorRepository
import net.kirkanos.uptimekuma.core.domain.TagRepository

/** Bindings of the monitor editor (create/edit/clone monitors) and tag management. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MonitorEditorModule {
    @Binds
    abstract fun bindMonitorEditorRepository(impl: KumaMonitorEditorRepository): MonitorEditorRepository

    @Binds
    abstract fun bindTagRepository(impl: KumaTagRepository): TagRepository

    @Binds
    abstract fun bindEditorRemote(impl: ManagerEditorRemote): EditorRemote
}
