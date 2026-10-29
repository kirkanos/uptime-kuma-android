package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.domain.KumaNotificationRepository
import net.kirkanos.uptimekuma.core.domain.MonitorEditorRepository
import net.kirkanos.uptimekuma.core.domain.MonitorRepository
import net.kirkanos.uptimekuma.core.domain.TagRepository
import net.kirkanos.uptimekuma.core.domain.TagSyncException
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorPayloadBuilder
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.TagDiff
import net.kirkanos.uptimekuma.core.domain.editor.asId
import net.kirkanos.uptimekuma.core.model.EditorPickers
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.PickerOption
import javax.inject.Inject
import javax.inject.Singleton

/** [MonitorEditorRepository] on top of Kuma's `getMonitor` / `add` / `editMonitor` and the monitor tag events. */
@Singleton
internal class KumaMonitorEditorRepository
    @Inject
    constructor(
        private val remote: EditorRemote,
        private val monitorRepository: MonitorRepository,
        private val notificationRepository: KumaNotificationRepository,
        private val tagRepository: TagRepository,
    ) : MonitorEditorRepository {
        private val spec: MonitorSpec get() = MonitorSpec.bundled
        private val builder by lazy { MonitorPayloadBuilder(spec) }

        override suspend fun loadForEdit(id: Long): MonitorDraft = MonitorDrafts.fromMonitorJson(fetchMonitor(id))

        override suspend fun newDraft(type: String): MonitorDraft {
            val pickers = observePickers().first()
            return MonitorDrafts.newDraft(
                spec = spec,
                type = type,
                notificationIds = pickers.notifications.filter { it.isDefault }.map { it.id },
                defaultProxyId = pickers.proxies.firstOrNull { it.isDefault }?.id,
            )
        }

        override suspend fun clone(
            id: Long,
            nameFor: (String) -> String,
        ): MonitorDraft {
            val source = loadForEdit(id)
            return MonitorDrafts.clone(spec, source, nameFor(source.name))
        }

        override suspend fun save(draft: MonitorDraft): Long {
            val existing = draft.id
            val id =
                if (existing == null) {
                    val ack = addTolerantToOlderServers(builder.forAdd(draft))
                    ack["monitorID"].let { JsonValues.toKotlin(it) }.asId()
                        ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "add ack without monitorID"))
                } else {
                    remote.call("editMonitor", JsonValues.toJson(builder.forEdit(draft)))
                    existing
                }
            try {
                syncTags(id, draft.tags, fresh = existing == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TagSyncException(id, e)
            }
            return id
        }

        /**
         * `add` imports the payload 1:1 as DB columns, so an older Kuma (e.g. 2.4) rejects fields that
         * only exist in newer versions with "SQLITE_ERROR: table monitor has no column named X". Drop
         * exactly that field and retry; the server then uses its own default for it.
         */
        private suspend fun addTolerantToOlderServers(payload: Map<String, Any?>): JsonObject {
            val fields = LinkedHashMap(payload)
            repeat(MAX_UNKNOWN_COLUMN_RETRIES) {
                try {
                    return remote.call("add", JsonValues.toJson(fields))
                } catch (e: KumaException) {
                    // Kuma (redbean) stores camelCase keys as snake_case columns and reports the column.
                    val column = UNKNOWN_COLUMN.find(e.error.message.orEmpty())?.groupValues?.get(1) ?: throw e
                    val key = fields.keys.firstOrNull { it == column || it.toSnakeCase() == column } ?: throw e
                    fields.remove(key)
                }
            }
            return remote.call("add", JsonValues.toJson(fields))
        }

        private suspend fun syncTags(
            monitorId: Long,
            desired: List<MonitorTag>,
            fresh: Boolean,
        ) {
            val current = if (fresh) emptyList() else MonitorDrafts.fromMonitorJson(fetchMonitor(monitorId)).tags
            val diff = TagDiff.of(current, desired)
            diff.toDelete.forEach { remote.call("deleteMonitorTag", it.tagId, monitorId, it.value) }
            diff.toEdit.forEach { (old, value) -> remote.call("editMonitorTag", old.tagId, monitorId, value) }
            diff.toAdd.forEach { remote.call("addMonitorTag", it.tagId, monitorId, it.value.trim()) }
        }

        override suspend fun delete(
            id: Long,
            deleteChildren: Boolean,
        ) {
            if (deleteChildren) {
                remote.call("deleteMonitor", id, true)
            } else {
                monitorRepository.delete(id)
            }
        }

        override fun observePickers(): Flow<EditorPickers> {
            val lists =
                combine(
                    remote.snapshot("proxyList"),
                    remote.snapshot("dockerHostList"),
                    remote.snapshot("remoteBrowserList"),
                ) { proxies, docker, browsers ->
                    Triple(
                        PickerDecoders.proxies(proxies),
                        PickerDecoders.named(docker),
                        PickerDecoders.named(browsers),
                    )
                }
            return combine(
                notificationRepository.observeNotifications(),
                monitorRepository.observeMonitors(),
                tagRepository.observeTags(),
                lists,
            ) { notifications, monitors, tags, (proxies, docker, browsers) ->
                EditorPickers(
                    notifications =
                        notifications.mapNotNull { n ->
                            n.id?.let { PickerOption(id = it, label = n.name, isDefault = n.isDefault) }
                        },
                    proxies = proxies,
                    dockerHosts = docker,
                    remoteBrowsers = browsers,
                    groups =
                        monitors
                            .map { it.monitor }
                            .filter { it.isGroup }
                            .map { PickerOption(id = it.id, label = it.pathName.ifBlank { it.name }) },
                    tags = tags,
                )
            }.distinctUntilChanged()
        }

        private suspend fun fetchMonitor(id: Long): Map<String, Any?> {
            val monitor = remote.call("getMonitor", id)["monitor"] as? JsonObject
            @Suppress("UNCHECKED_CAST")
            return (JsonValues.toKotlin(monitor) as? Map<String, Any?>)
                ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "getMonitor ack without monitor"))
        }

        private fun String.toSnakeCase(): String = replace(CAMEL_HUMP) { "_" + it.value.lowercase() }

        private companion object {
            val CAMEL_HUMP = Regex("[A-Z]")
            const val MAX_UNKNOWN_COLUMN_RETRIES = 8
            val UNKNOWN_COLUMN = Regex("""no column named (\w+)""")
        }
    }
