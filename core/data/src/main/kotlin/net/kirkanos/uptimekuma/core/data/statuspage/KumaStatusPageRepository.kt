package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.data.connection.DataLog
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.kumaclient.callOk
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StatusPageRepository] on top of the shared socket ([KumaConnectionManager.withApi]) and the public REST API.
 *
 * Kuma pushes `statusPageList` only after login, not after `addStatusPage`/`saveStatusPage`/`deleteStatusPage`,
 * so local changes are layered over the last snapshot until the server sends a new one (next login/reconnect).
 */
@Singleton
internal class KumaStatusPageRepository
    @Inject
    constructor(
        private val manager: KumaConnectionManager,
        private val rest: StatusPageRestClient,
    ) : StatusPageRepository {
        /** Local changes on top of the snapshot [base] they were made against. */
        private data class Edits(
            val base: List<JsonElement>? = null,
            val upserts: Map<String, StatusPageConfig> = emptyMap(),
            val removed: Set<String> = emptySet(),
        )

        private val edits = MutableStateFlow(Edits())

        /** Admin config objects as returned by `getStatusPage`, echoed back by [save] like the web UI. */
        private val rawConfigs = ConcurrentHashMap<String, JsonObject>()

        private val snapshot: StateFlow<List<JsonElement>?> get() = manager.snapshot(EVENT_LIST)

        override fun observeStatusPages(): Flow<List<StatusPageConfig>?> =
            combine(snapshot, edits) { args, edits ->
                val list = StatusPageJson.configList(args) ?: return@combine null
                if (edits.base !== args) return@combine list
                (list.filterNot { it.slug in edits.removed || it.slug in edits.upserts } + edits.upserts.values)
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }.distinctUntilChanged()

        override suspend fun refreshList() {
            manager.withApi { withTimeoutOrNull(LIST_WAIT_MILLIS) { snapshot.first { it != null } } }
        }

        override suspend fun loadConfig(slug: String): StatusPageConfig = StatusPageJson.config(fetchRawConfig(slug))

        override suspend fun loadPublic(
            slug: String,
            address: StatusPageAddress?,
        ): PublicStatusPage {
            val page = rest.page(address?.baseUrl ?: serverBaseUrl(), slug)
            if (address != null) return page
            // The REST page is cached for 5 min and postIncident/unpinIncident do not clear that cache:
            // take the pinned incident from the (uncached) socket history when logged in.
            val pinned =
                try {
                    loadIncidentHistory(slug, null).incidents.filter { it.pin && it.active }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: KumaException) {
                    return page
                }
            return page.copy(incidents = pinned)
        }

        override suspend fun loadHeartbeats(
            slug: String,
            address: StatusPageAddress?,
        ): StatusPageHeartbeats = rest.heartbeats(address?.baseUrl ?: serverBaseUrl(), slug)

        override suspend fun create(
            title: String,
            slug: String,
        ): String {
            val normalized = StatusPageSlug.normalize(slug)
            val ack = manager.withApi { it.callOk("addStatusPage", title.trim(), normalized) }
            val created = ack["slug"].asStringOrNull() ?: normalized
            val config =
                try {
                    loadConfig(created)
                } catch (e: KumaException) {
                    DataLog.w("getStatusPage after add failed", e)
                    StatusPageConfig(slug = created, title = title.trim())
                }
            applyEdit { it.copy(upserts = it.upserts + (created to config), removed = it.removed - created) }
            return created
        }

        override suspend fun save(
            slug: String,
            config: StatusPageConfig,
            groups: List<StatusPageGroup>,
        ): List<StatusPageGroup> {
            val raw = rawConfigs[slug] ?: fetchRawConfig(slug)
            val payload = StatusPageJson.configPayload(raw, config)
            val ack =
                manager.withApi {
                    it.callOk(
                        "saveStatusPage",
                        slug,
                        payload,
                        StatusPageJson.imgDataUrl(config),
                        StatusPageJson.groupsPayload(groups),
                    )
                }
            val newSlug = StatusPageSlug.normalize(config.slug)
            rawConfigs.remove(slug)
            rawConfigs[newSlug] = payload
            val saved = StatusPageJson.config(payload)
            applyEdit {
                it.copy(
                    upserts = it.upserts - slug + (newSlug to saved),
                    removed = (it.removed + slug - newSlug),
                )
            }
            return StatusPageJson.savedGroups(ack, groups)
        }

        override suspend fun delete(slug: String) {
            manager.withApi { it.callOk("deleteStatusPage", slug) }
            rawConfigs.remove(slug)
            applyEdit { it.copy(upserts = it.upserts - slug, removed = it.removed + slug) }
        }

        override suspend fun postIncident(
            slug: String,
            incident: IncidentDraft,
        ): Incident {
            val ack = manager.withApi { it.callOk("postIncident", slug, StatusPageJson.incidentPayload(incident)) }
            val obj =
                ack["incident"] as? JsonObject
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "postIncident ack without incident"))
            return StatusPageJson.incident(obj)
        }

        override suspend fun unpinIncident(slug: String) {
            manager.withApi { it.callOk("unpinIncident", slug) }
        }

        override suspend fun loadIncidentHistory(
            slug: String,
            cursor: String?,
        ): IncidentHistoryPage =
            StatusPageJson.history(manager.withApi { it.callOk("getIncidentHistory", slug, cursor) })

        override suspend fun loadPublicIncidentHistory(
            address: StatusPageAddress,
            cursor: String?,
        ): IncidentHistoryPage = rest.incidentHistory(address.baseUrl, address.slug, cursor)

        private suspend fun fetchRawConfig(slug: String): JsonObject {
            val ack = manager.withApi { it.callOk("getStatusPage", slug) }
            val config =
                ack["config"] as? JsonObject
                    ?: throw KumaException(KumaError(KumaError.Kind.UNKNOWN, "getStatusPage ack without config"))
            rawConfigs[slug] = config
            return config
        }

        private suspend fun serverBaseUrl(): String =
            manager.serverConfig.first()?.baseUrl
                ?: throw KumaException(KumaError(KumaError.Kind.UNAUTHORIZED, "No server configured"))

        private fun applyEdit(change: (Edits) -> Edits) {
            val base = snapshot.value
            edits.update { current -> change(if (current.base === base) current else Edits(base = base)) }
        }

        companion object {
            const val EVENT_LIST = "statusPageList"
            private const val LIST_WAIT_MILLIS = 5_000L
        }
    }
