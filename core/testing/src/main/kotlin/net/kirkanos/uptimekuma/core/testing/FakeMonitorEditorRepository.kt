package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.kirkanos.uptimekuma.core.domain.MonitorEditorRepository
import net.kirkanos.uptimekuma.core.domain.TagRepository
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.PickerOption
import net.kirkanos.uptimekuma.core.model.Tag

/**
 * In-memory [MonitorEditorRepository] using the real bundled spec for drafts. [stored] holds saved monitors
 * by id; [saved] records every [save] call. Set [error] to make suspend calls fail.
 */
class FakeMonitorEditorRepository(
    initial: Map<Long, MonitorDraft> = emptyMap(),
    initialPickers: EditorPickers = samplePickers,
) : MonitorEditorRepository {
    val stored = initial.toMutableMap()
    val pickers = MutableStateFlow(initialPickers)
    val saved = mutableListOf<MonitorDraft>()
    val deleted = mutableListOf<Pair<Long, Boolean>>()
    var error: Exception? = null

    /** Thrown by [save] only (after recording the call), e.g. a validation error from the server. */
    var saveError: Exception? = null
    var delayMillis: Long = 0
    private var nextId = (initial.keys.maxOrNull() ?: 0L) + 1

    override suspend fun loadForEdit(id: Long): MonitorDraft {
        simulate()
        return stored[id] ?: throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Monitor not found"))
    }

    override suspend fun newDraft(type: String): MonitorDraft {
        simulate()
        val p = pickers.value
        return MonitorDrafts.newDraft(
            MonitorSpec.bundled,
            type,
            p.notifications.filter { it.isDefault }.map { it.id },
            p.proxies.firstOrNull { it.isDefault }?.id,
        )
    }

    override suspend fun clone(
        id: Long,
        nameFor: (String) -> String,
    ): MonitorDraft {
        val source = loadForEdit(id)
        return MonitorDrafts.clone(MonitorSpec.bundled, source, nameFor(source.name))
    }

    override suspend fun save(draft: MonitorDraft): Long {
        simulate()
        saved += draft
        saveError?.let { throw it }
        val id = draft.id ?: nextId++
        stored[id] = draft.copy(id = id, fields = draft.fields + ("id" to id))
        return id
    }

    override suspend fun delete(
        id: Long,
        deleteChildren: Boolean,
    ) {
        simulate()
        deleted += id to deleteChildren
        stored.remove(id)
    }

    override fun observePickers(): Flow<EditorPickers> = pickers

    private suspend fun simulate() {
        if (delayMillis > 0) delay(delayMillis)
        error?.let { throw it }
    }

    companion object {
        val samplePickers =
            EditorPickers(
                notifications = listOf(PickerOption(1, "ntfy phone", isDefault = true), PickerOption(2, "Mail")),
                proxies = listOf(PickerOption(5, "http://proxy.lan:3128")),
                dockerHosts = listOf(PickerOption(7, "local socket")),
                remoteBrowsers = emptyList(),
                groups = listOf(PickerOption(11, "Group Web")),
                tags = SampleData.tags,
            )
    }
}

/** In-memory [TagRepository]. */
class FakeTagRepository(
    initial: List<Tag> = SampleData.tags,
) : TagRepository {
    val tags = MutableStateFlow(initial)
    var error: Exception? = null
    var refreshCount = 0
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeTags(): Flow<List<Tag>> = tags

    override suspend fun refresh() {
        error?.let { throw it }
        refreshCount++
    }

    override suspend fun create(
        name: String,
        color: String,
    ): Tag {
        error?.let { throw it }
        val tag = Tag(nextId++, name, color)
        tags.value = tags.value + tag
        return tag
    }

    override suspend fun update(tag: Tag): Tag {
        error?.let { throw it }
        tags.value = tags.value.map { if (it.id == tag.id) tag else it }
        return tag
    }

    override suspend fun delete(id: Long) {
        error?.let { throw it }
        tags.value = tags.value.filterNot { it.id == id }
    }
}
