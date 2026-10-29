package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.TagRepository
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.Tag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tags from `getTags` (kept in memory, refreshed on demand and after every change), falling back to the
 * tags cached at login. After renaming/deleting a tag the monitor list is re-requested so the cached
 * monitor tags pick up the change (Kuma does not push `updateMonitorIntoList` for tag definitions).
 */
@Singleton
internal class KumaTagRepository
    @Inject
    constructor(
        private val remote: EditorRemote,
        private val monitorRepository: MonitorRepository,
    ) : TagRepository {
        private val fetched = MutableStateFlow<List<Tag>?>(null)

        override fun observeTags(): Flow<List<Tag>> =
            combine(fetched, monitorRepository.observeTags()) { live, cached ->
                (live ?: cached).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }.distinctUntilChanged()

        override suspend fun refresh() {
            fetched.value = PickerDecoders.tags(remote.call("getTags")["tags"])
        }

        override suspend fun create(
            name: String,
            color: String,
        ): Tag {
            val ack = remote.call("addTag", tagJson(null, name, color))
            val tag = PickerDecoders.tag(ack["tag"]) ?: throw unexpected("addTag")
            fetched.update { (it ?: emptyList()).filterNot { t -> t.id == tag.id } + tag }
            return tag
        }

        override suspend fun update(tag: Tag): Tag {
            val ack = remote.call("editTag", tagJson(tag.id, tag.name, tag.color))
            val saved = PickerDecoders.tag(ack["tag"]) ?: tag
            fetched.update { list -> list?.map { if (it.id == saved.id) saved else it } }
            reloadMonitorList()
            return saved
        }

        override suspend fun delete(id: Long) {
            remote.call("deleteTag", id)
            fetched.update { list -> list?.filterNot { it.id == id } }
            reloadMonitorList()
        }

        private suspend fun reloadMonitorList() {
            try {
                remote.call("getMonitorList")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: the next login sync refreshes the cache anyway.
            }
        }

        private fun tagJson(
            id: Long?,
            name: String,
            color: String,
        ) = JsonObject(
            buildMap {
                if (id != null) put("id", JsonPrimitive(id))
                put("name", JsonPrimitive(name.trim()))
                put("color", JsonPrimitive(color))
            },
        )

        private fun unexpected(event: String) =
            KumaException(KumaError(KumaError.Kind.UNKNOWN, "$event ack without tag"))
    }
