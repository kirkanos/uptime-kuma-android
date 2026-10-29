@file:Suppress("MagicNumber")

package net.kirkanos.uptimekuma.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.kirkanos.uptimekuma.core.domain.StatusPageRepository
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import net.kirkanos.uptimekuma.core.model.StatusPageMaintenance
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.model.StatusPageSlug

/**
 * In-memory [StatusPageRepository]. Admin calls mutate [pages] and [publicPages] like the server would.
 * Set [error] to fail every call, [publicError] to fail only the REST reads.
 */
@Suppress("TooManyFunctions")
class FakeStatusPageRepository(
    initial: List<StatusPageConfig>? = emptyList(),
) : StatusPageRepository {
    val pages = MutableStateFlow(initial)
    val publicPages = mutableMapOf<String, PublicStatusPage>()
    val heartbeats = mutableMapOf<String, StatusPageHeartbeats>()

    /** Incident history per slug, newest first. */
    val history = mutableMapOf<String, List<Incident>>()

    var error: KumaException? = null
    var publicError: KumaException? = null
    var delayMillis: Long = 0

    val created = mutableListOf<Pair<String, String>>()
    val saved = mutableListOf<Triple<String, StatusPageConfig, List<StatusPageGroup>>>()
    val deleted = mutableListOf<String>()
    val postedIncidents = mutableListOf<Pair<String, IncidentDraft>>()
    val unpinned = mutableListOf<String>()
    val publicRequests = mutableListOf<Pair<String, StatusPageAddress?>>()
    var refreshCount = 0
        private set
    private var nextId = 100L

    override fun observeStatusPages(): Flow<List<StatusPageConfig>?> = pages

    override suspend fun refreshList() {
        refreshCount++
        simulate()
        if (pages.value == null) pages.value = emptyList()
    }

    override suspend fun loadConfig(slug: String): StatusPageConfig {
        simulate()
        return pages.value.orEmpty().firstOrNull { it.slug == slug } ?: throw notFound()
    }

    override suspend fun loadPublic(
        slug: String,
        address: StatusPageAddress?,
    ): PublicStatusPage {
        publicRequests += slug to address
        simulatePublic()
        return publicPages[slug] ?: throw notFound()
    }

    override suspend fun loadHeartbeats(
        slug: String,
        address: StatusPageAddress?,
    ): StatusPageHeartbeats {
        simulatePublic()
        return heartbeats[slug] ?: StatusPageHeartbeats()
    }

    override suspend fun create(
        title: String,
        slug: String,
    ): String {
        simulate()
        val normalized = StatusPageSlug.normalize(slug)
        if (pages.value.orEmpty().any { it.slug == normalized }) {
            throw KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "UNIQUE constraint failed: status_page.slug"))
        }
        created += title to normalized
        val config = StatusPageConfig(id = nextId++, slug = normalized, title = title, icon = "")
        pages.update { it.orEmpty() + config }
        publicPages[normalized] = PublicStatusPage(config.copy(id = null))
        return normalized
    }

    override suspend fun save(
        slug: String,
        config: StatusPageConfig,
        groups: List<StatusPageGroup>,
    ): List<StatusPageGroup> {
        simulate()
        saved += Triple(slug, config, groups)
        val withIds = groups.map { if (it.id == null) it.copy(id = nextId++) else it }
        pages.update { list -> list.orEmpty().map { if (it.slug == slug) config else it } }
        val old = publicPages.remove(slug) ?: PublicStatusPage(config)
        publicPages[config.slug] = old.copy(config = config, groups = withIds)
        return withIds
    }

    override suspend fun delete(slug: String) {
        simulate()
        deleted += slug
        pages.update { list -> list.orEmpty().filterNot { it.slug == slug } }
        publicPages.remove(slug)
    }

    override suspend fun postIncident(
        slug: String,
        incident: IncidentDraft,
    ): Incident {
        simulate()
        postedIncidents += slug to incident
        val posted =
            Incident(
                id = incident.id ?: nextId++,
                title = incident.title,
                content = incident.content,
                style = incident.style,
                createdMillis = 0,
            )
        history[slug] = listOf(posted) + history[slug].orEmpty().filterNot { it.id == posted.id }
        publicPages[slug]?.let { publicPages[slug] = it.copy(incidents = listOf(posted)) }
        return posted
    }

    override suspend fun unpinIncident(slug: String) {
        simulate()
        unpinned += slug
        history[slug] = history[slug].orEmpty().map { it.copy(pin = false) }
        publicPages[slug]?.let { publicPages[slug] = it.copy(incidents = emptyList()) }
    }

    override suspend fun loadIncidentHistory(
        slug: String,
        cursor: String?,
    ): IncidentHistoryPage {
        simulate()
        return page(history[slug].orEmpty(), cursor)
    }

    override suspend fun loadPublicIncidentHistory(
        address: StatusPageAddress,
        cursor: String?,
    ): IncidentHistoryPage {
        simulatePublic()
        return page(history[address.slug].orEmpty(), cursor)
    }

    private fun page(
        all: List<Incident>,
        cursor: String?,
    ): IncidentHistoryPage {
        val start = cursor?.toIntOrNull() ?: 0
        val items = all.drop(start).take(PAGE_SIZE)
        val end = start + items.size
        val hasMore = end < all.size
        return IncidentHistoryPage(items, all.size, if (hasMore) end.toString() else null, hasMore)
    }

    private suspend fun simulate() {
        if (delayMillis > 0) delay(delayMillis)
        error?.let { throw it }
    }

    private suspend fun simulatePublic() {
        simulate()
        publicError?.let { throw it }
    }

    private fun notFound() = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "Status Page Not Found"))

    companion object {
        const val PAGE_SIZE = 10

        /** A repository with one page `status` (two groups, an incident, a maintenance and heartbeats). */
        fun withSampleData(nowMillis: Long = System.currentTimeMillis()): FakeStatusPageRepository {
            val config =
                StatusPageConfig(
                    id = 1,
                    slug = "status",
                    title = "Example status",
                    description = "Public status of the example services.",
                    icon = "/icon.svg",
                    footerText = "Operated by Example",
                    showTags = true,
                    showCertificateExpiry = true,
                )
            val groups =
                listOf(
                    StatusPageGroup(
                        id = 1,
                        name = "Services",
                        monitors =
                            listOf(
                                StatusPageMonitor(1, "Website", "http", sendUrl = true, url = "https://example.org"),
                                StatusPageMonitor(2, "API", "http", certExpiryDaysRemaining = 42, validCert = true),
                            ),
                    ),
                    StatusPageGroup(
                        id = 2,
                        name = "Infrastructure",
                        monitors = listOf(StatusPageMonitor(3, "Database", "port")),
                    ),
                )
            val incident =
                Incident(
                    id = 1,
                    title = "Degraded API performance",
                    content = "We are investigating slow responses.",
                    style = IncidentStyle.WARNING,
                    createdMillis = nowMillis - HOUR,
                )

            fun beats(
                id: Long,
                last: MonitorStatus,
            ) = List(BEATS) { i ->
                Heartbeat(
                    monitorId = id,
                    status = if (i == BEATS - 1) last else MonitorStatus.UP,
                    timeMillis = nowMillis - (BEATS - i) * MINUTE,
                    pingMs = 80.0 + i,
                )
            }
            return FakeStatusPageRepository(listOf(config)).apply {
                publicPages["status"] =
                    PublicStatusPage(
                        config = config.copy(id = null),
                        incidents = listOf(incident),
                        groups = groups,
                        maintenances =
                            listOf(
                                StatusPageMaintenance(1, "Database upgrade", status = "under-maintenance"),
                            ),
                    )
                heartbeats["status"] =
                    StatusPageHeartbeats(
                        beats =
                            mapOf(
                                1L to beats(1, MonitorStatus.UP),
                                2L to beats(2, MonitorStatus.DOWN),
                                3L to beats(3, MonitorStatus.UP),
                            ),
                        uptime24h = mapOf(1L to 1.0, 2L to 0.9712, 3L to 0.9995),
                    )
                history["status"] = listOf(incident)
            }
        }

        private const val BEATS = 50
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
    }
}
