package net.kirkanos.uptimekuma.core.domain

import kotlinx.coroutines.flow.Flow
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageAddress
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats

/**
 * Status pages of the logged-in server (Socket.IO) plus read-only access to any public status page
 * over REST (no login). All suspend functions throw [net.kirkanos.uptimekuma.core.model.KumaException].
 *
 * Public reads ([loadPublic], [loadHeartbeats], [loadPublicIncidentHistory]) go through Kuma's cached REST
 * endpoints (5 min / 1 min); admin changes clear that cache except for incidents.
 */
interface StatusPageRepository {
    /** Status pages (`statusPageList`, sorted by title); `null` until the server sent the list after login. */
    fun observeStatusPages(): Flow<List<StatusPageConfig>?>

    /** Makes sure a session exists so the list gets pushed; waits (bounded) until it is known. */
    suspend fun refreshList()

    /** Full admin settings (`getStatusPage`). */
    suspend fun loadConfig(slug: String): StatusPageConfig

    /** Public data of [slug] on the logged-in server ([address] `null`) or on any server. */
    suspend fun loadPublic(
        slug: String,
        address: StatusPageAddress? = null,
    ): PublicStatusPage

    suspend fun loadHeartbeats(
        slug: String,
        address: StatusPageAddress? = null,
    ): StatusPageHeartbeats

    /** `addStatusPage`; returns the stored (normalized) slug. */
    suspend fun create(
        title: String,
        slug: String,
    ): String

    /**
     * `saveStatusPage` under the current [slug]; [config] may carry a new slug. The logo is kept as is.
     * Returns the groups with their ids filled in.
     */
    suspend fun save(
        slug: String,
        config: StatusPageConfig,
        groups: List<StatusPageGroup>,
    ): List<StatusPageGroup>

    suspend fun delete(slug: String)

    /** Posts (or with [IncidentDraft.id] updates) the pinned incident. */
    suspend fun postIncident(
        slug: String,
        incident: IncidentDraft,
    ): Incident

    /** Unpins all pinned incidents of the page. */
    suspend fun unpinIncident(slug: String)

    /** Incident history via the socket (uncached, logged in). [cursor] `null` = first page. */
    suspend fun loadIncidentHistory(
        slug: String,
        cursor: String?,
    ): IncidentHistoryPage

    /** Incident history over REST, for public viewing. */
    suspend fun loadPublicIncidentHistory(
        address: StatusPageAddress,
        cursor: String?,
    ): IncidentHistoryPage
}
