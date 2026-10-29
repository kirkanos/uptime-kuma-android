package net.kirkanos.uptimekuma.core.data.statuspage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kirkanos.uptimekuma.core.kumaclient.KumaTime
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientDouble
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.model.Heartbeat
import net.kirkanos.uptimekuma.core.model.Incident
import net.kirkanos.uptimekuma.core.model.IncidentDraft
import net.kirkanos.uptimekuma.core.model.IncidentHistoryPage
import net.kirkanos.uptimekuma.core.model.IncidentStyle
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.MonitorTag
import net.kirkanos.uptimekuma.core.model.PublicStatusPage
import net.kirkanos.uptimekuma.core.model.StatusPageAnalytics
import net.kirkanos.uptimekuma.core.model.StatusPageConfig
import net.kirkanos.uptimekuma.core.model.StatusPageGroup
import net.kirkanos.uptimekuma.core.model.StatusPageHeartbeats
import net.kirkanos.uptimekuma.core.model.StatusPageMaintenance
import net.kirkanos.uptimekuma.core.model.StatusPageMonitor
import net.kirkanos.uptimekuma.core.model.StatusPageSlug
import net.kirkanos.uptimekuma.core.model.StatusPageTheme

/**
 * Decoding of Kuma's status page payloads (socket acks, `statusPageList`, public REST) and the
 * payloads the web UI sends (`saveStatusPage`, `postIncident`). Lenient: unknown/missing fields are tolerated.
 */
@Suppress("TooManyFunctions")
internal object StatusPageJson {
    /** Logo the web UI sends when the page has none (`StatusPage.vue` `imgDataUrl` default). */
    const val DEFAULT_ICON = "/icon.svg"
    private const val UPTIME_24H_SUFFIX = "_24"

    // ---- decoding -------------------------------------------------------------------------------

    /** `statusPageList` args: `[{ "<id>": StatusPageJSON }]`, sorted by title like the server's query. */
    fun configList(args: List<JsonElement>?): List<StatusPageConfig>? {
        val map = args?.firstOrNull() as? JsonObject ?: return null
        return map.values
            .mapNotNull { (it as? JsonObject)?.let(::config) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun config(obj: JsonObject): StatusPageConfig =
        StatusPageConfig(
            id = obj["id"].asLenientLong(),
            slug = obj["slug"].asStringOrNull().orEmpty(),
            title = obj["title"].asStringOrNull().orEmpty(),
            description = obj["description"].asStringOrNull(),
            icon = obj["icon"].asStringOrNull(),
            theme = StatusPageTheme.fromKey(obj["theme"].asStringOrNull()),
            autoRefreshInterval =
                obj["autoRefreshInterval"].asLenientLong()?.toInt() ?: StatusPageConfig.DEFAULT_REFRESH_SECONDS,
            published = obj["published"].asLenientBoolean() ?: true,
            showTags = obj["showTags"].asLenientBoolean() ?: false,
            domainNameList =
                (obj["domainNameList"] as? JsonArray).orEmpty().mapNotNull { it.asStringOrNull() },
            customCss = obj["customCSS"].asStringOrNull(),
            footerText = obj["footerText"].asStringOrNull(),
            showPoweredBy = obj["showPoweredBy"].asLenientBoolean() ?: true,
            analyticsType = StatusPageAnalytics.fromKey(obj["analyticsType"].asStringOrNull()),
            analyticsId = obj["analyticsId"].asStringOrNull(),
            analyticsScriptUrl = obj["analyticsScriptUrl"].asStringOrNull(),
            showCertificateExpiry = obj["showCertificateExpiry"].asLenientBoolean() ?: false,
            showOnlyLastHeartbeat = obj["showOnlyLastHeartbeat"].asLenientBoolean() ?: false,
            rssTitle = obj["rssTitle"].asStringOrNull(),
        )

    /** `StatusPageData` of `GET /api/status-page/<slug>`; the public config has no `slug` fallback, so pass it. */
    fun publicPage(
        body: JsonObject,
        slug: String,
    ): PublicStatusPage {
        val configObj = body["config"] as? JsonObject ?: JsonObject(emptyMap())
        val config = config(configObj).let { if (it.slug.isEmpty()) it.copy(slug = slug) else it }
        return PublicStatusPage(
            config = config,
            incidents = (body["incidents"] as? JsonArray).objects().map(::incident),
            groups = (body["publicGroupList"] as? JsonArray).objects().map(::group),
            maintenances = (body["maintenanceList"] as? JsonArray).objects().map(::maintenance),
        )
    }

    fun group(obj: JsonObject): StatusPageGroup =
        StatusPageGroup(
            id = obj["id"].asLenientLong(),
            name = obj["name"].asStringOrNull().orEmpty(),
            monitors = (obj["monitorList"] as? JsonArray).objects().mapNotNull(::monitor),
        )

    fun monitor(obj: JsonObject): StatusPageMonitor? {
        val id = obj["id"].asLenientLong() ?: return null
        return StatusPageMonitor(
            id = id,
            name = obj["name"].asStringOrNull().orEmpty(),
            type = obj["type"].asStringOrNull(),
            sendUrl = obj["sendUrl"].asLenientBoolean() ?: false,
            url = obj["url"].asStringOrNull(),
            tags =
                (obj["tags"] as? JsonArray).objects().map { tag ->
                    MonitorTag(
                        tagId = tag["tag_id"].asLenientLong() ?: tag["id"].asLenientLong() ?: 0,
                        name = tag["name"].asStringOrNull().orEmpty(),
                        color = tag["color"].asStringOrNull().orEmpty(),
                        value = tag["value"].asStringOrNull().orEmpty(),
                    )
                },
            certExpiryDaysRemaining = obj["certExpiryDaysRemaining"].asLenientLong()?.toInt(),
            validCert = obj["validCert"].asLenientBoolean(),
        )
    }

    fun incident(obj: JsonObject): Incident =
        Incident(
            id = obj["id"].asLenientLong() ?: 0,
            title = obj["title"].asStringOrNull().orEmpty(),
            content = obj["content"].asStringOrNull(),
            style = IncidentStyle.fromKey(obj["style"].asStringOrNull()),
            pin = obj["pin"].asLenientBoolean() ?: false,
            active = obj["active"].asLenientBoolean() ?: false,
            createdMillis = KumaTime.parseUtcMillis(obj["createdDate"].asStringOrNull()),
            lastUpdatedMillis = KumaTime.parseUtcMillis(obj["lastUpdatedDate"].asStringOrNull()),
        )

    fun maintenance(obj: JsonObject): StatusPageMaintenance =
        StatusPageMaintenance(
            id = obj["id"].asLenientLong() ?: 0,
            title = obj["title"].asStringOrNull().orEmpty(),
            description = obj["description"].asStringOrNull()?.takeIf { it.isNotBlank() },
            status = obj["status"].asStringOrNull(),
        )

    /** `GET /api/status-page/heartbeat/<slug>`. */
    fun heartbeats(body: JsonObject): StatusPageHeartbeats {
        val beats =
            (body["heartbeatList"] as? JsonObject)
                .orEmpty()
                .mapNotNull { (key, value) ->
                    val monitorId = key.toLongOrNull() ?: return@mapNotNull null
                    val list =
                        (value as? JsonArray).objects().mapNotNull { beat ->
                            val time = KumaTime.parseUtcMillis(beat["time"].asStringOrNull()) ?: return@mapNotNull null
                            Heartbeat(
                                monitorId = monitorId,
                                status = MonitorStatus.fromCode(beat["status"].asLenientLong()?.toInt() ?: -1),
                                timeMillis = time,
                                message = beat["msg"].asStringOrNull().orEmpty(),
                                pingMs = beat["ping"].asLenientDouble(),
                            )
                        }
                    monitorId to list.sortedBy { it.timeMillis }
                }.toMap()
        val uptime =
            (body["uptimeList"] as? JsonObject)
                .orEmpty()
                .mapNotNull { (key, value) ->
                    if (!key.endsWith(UPTIME_24H_SUFFIX)) return@mapNotNull null
                    val monitorId = key.removeSuffix(UPTIME_24H_SUFFIX).toLongOrNull() ?: return@mapNotNull null
                    value.asLenientDouble()?.let { monitorId to it }
                }.toMap()
        return StatusPageHeartbeats(beats, uptime)
    }

    /** `getIncidentHistory` ack / REST `incident-history` body. */
    fun history(body: JsonObject): IncidentHistoryPage {
        val incidents = (body["incidents"] as? JsonArray).objects().map(::incident)
        return IncidentHistoryPage(
            incidents = incidents,
            total = body["total"].asLenientLong()?.toInt() ?: incidents.size,
            nextCursor = body["nextCursor"].asStringOrNull(),
            hasMore = body["hasMore"].asLenientBoolean() ?: false,
        )
    }

    // ---- payloads -------------------------------------------------------------------------------

    /**
     * `config` argument of `saveStatusPage`. Like the web UI this is the admin config object as loaded by
     * `getStatusPage` ([raw], unknown fields kept) with the edited fields written over it. `analyticsType` is
     * always present (the server rejects a missing one as "Invalid analytics type"); the slug is trimmed and
     * lower-cased like `StatusPage.vue` `save()`.
     */
    fun configPayload(
        raw: JsonObject?,
        config: StatusPageConfig,
    ): JsonObject {
        val edited =
            buildJsonObject {
                config.id?.let { put("id", it) }
                put("slug", StatusPageSlug.normalize(config.slug))
                put("title", config.title.trim())
                put("description", config.description)
                put("footerText", config.footerText)
                put("icon", config.icon)
                put("theme", config.theme.key)
                put("autoRefreshInterval", config.autoRefreshInterval)
                put("published", config.published)
                put("showTags", config.showTags)
                put("showPoweredBy", config.showPoweredBy)
                put("showCertificateExpiry", config.showCertificateExpiry)
                put("showOnlyLastHeartbeat", config.showOnlyLastHeartbeat)
                put("customCSS", config.customCss)
                put("rssTitle", config.rssTitle?.takeIf { it.isNotBlank() })
                put("analyticsType", config.analyticsType?.key)
                put("analyticsId", config.analyticsType?.let { config.analyticsId?.trim() })
                put(
                    "analyticsScriptUrl",
                    config.analyticsType?.takeIf { it.usesScriptUrl }?.let { config.analyticsScriptUrl?.trim() },
                )
                put(
                    "domainNameList",
                    buildJsonArray {
                        config.domainNameList
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        return JsonObject(raw.orEmpty() + edited)
    }

    /** `imgDataUrl` argument: the current logo path/URL unchanged (keeps the logo), else the web UI default. */
    fun imgDataUrl(config: StatusPageConfig): String = config.icon?.takeIf { it.isNotBlank() } ?: DEFAULT_ICON

    /**
     * `publicGroupList` argument: `[{id?, name, monitorList:[{id, sendUrl, url?}]}]` in display order.
     * The server only reads `id`, `sendUrl` and `url` of a monitor; `url` (stored as custom URL) is sent only when set.
     */
    fun groupsPayload(groups: List<StatusPageGroup>): JsonArray =
        buildJsonArray {
            groups.forEach { group ->
                add(
                    buildJsonObject {
                        group.id?.let { put("id", it) }
                        put("name", group.name.trim())
                        put(
                            "monitorList",
                            buildJsonArray {
                                group.monitors.forEach { monitor ->
                                    add(
                                        buildJsonObject {
                                            put("id", monitor.id)
                                            put("sendUrl", monitor.sendUrl)
                                            monitor.url
                                                ?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { put("url", it) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

    /** `postIncident` incident argument (`{id?, title, content, style}`). */
    fun incidentPayload(draft: IncidentDraft): JsonObject =
        buildJsonObject {
            draft.id?.let { put("id", it) }
            put("title", draft.title.trim())
            put("content", draft.content.trim())
            put("style", draft.style.key)
        }

    /**
     * Merges the `publicGroupList` returned by `saveStatusPage` (ids filled in) back into the sent groups.
     * Falls back to the sent groups when the ack has an unexpected shape.
     */
    fun savedGroups(
        ack: JsonObject,
        sent: List<StatusPageGroup>,
    ): List<StatusPageGroup> {
        val returned = (ack["publicGroupList"] as? JsonArray).objects()
        if (returned.size != sent.size) return sent
        return sent.mapIndexed { index, group -> group.copy(id = returned[index]["id"].asLenientLong() ?: group.id) }
    }

    private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
