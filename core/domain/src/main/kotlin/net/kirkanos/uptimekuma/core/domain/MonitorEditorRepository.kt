package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.Tag

/**
 * Create, edit, clone and delete monitors of every Kuma type. Field semantics come from
 * `spec/monitor-types.json` (see [net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec]).
 * Suspend functions throw [net.kirkanos.uptimekuma.core.model.KumaException] on failure.
 */
interface MonitorEditorRepository {
    /** `getMonitor` (with sensitive fields) as an editable draft incl. its tags. */
    suspend fun loadForEdit(id: Long): MonitorDraft

    /**
     * A new monitor of [type] with the web UI's defaults, the default notifications and the default
     * proxy pre-selected and (for push monitors) a fresh push token.
     */
    suspend fun newDraft(type: String): MonitorDraft

    /**
     * Loads monitor [id] and turns it into an unsaved copy: no id, name from [nameFor] (e.g. "Copy of X"),
     * a new push token, same tags.
     */
    suspend fun clone(
        id: Long,
        nameFor: (String) -> String = { "Copy of $it" },
    ): MonitorDraft

    /**
     * `add` (draft without id) or `editMonitor`, then syncs the tags (add/edit/delete monitor tags
     * against the server state). Returns the monitor id.
     */
    suspend fun save(draft: MonitorDraft): Long

    /** `deleteMonitor`; [deleteChildren] removes the children of a group too (otherwise they are unlinked). */
    suspend fun delete(
        id: Long,
        deleteChildren: Boolean = false,
    )

    /** Choices for notifications, proxies, docker hosts, remote browsers, parent groups and tags. */
    fun observePickers(): Flow<EditorPickers>
}

/**
 * Thrown by [MonitorEditorRepository.save] when the monitor itself was saved (it has [monitorId] now) but
 * attaching/detaching tags failed. Saving again edits the monitor and retries the tags.
 */
class TagSyncException(
    val monitorId: Long,
    override val cause: Throwable,
) : Exception("Monitor $monitorId saved, but its tags could not be updated", cause)

/** Tag definitions (`getTags`, `addTag`, `editTag`, `deleteTag`). */
interface TagRepository {
    /** All tags of the server, sorted by name. Emits the cached list first. */
    fun observeTags(): Flow<List<Tag>>

    /** Re-reads the tags from the server (`getTags`). */
    suspend fun refresh()

    suspend fun create(
        name: String,
        color: String,
    ): Tag

    suspend fun update(tag: Tag): Tag

    /** Deletes the tag and detaches it from all monitors. */
    suspend fun delete(id: Long)
}
