package net.kirkanos.uptimekuma.core.model

/**
 * Status page settings. Built from Kuma's admin `StatusPageJSON` (`statusPageList`, `getStatusPage`)
 * or the public `config` of `GET /api/status-page/<slug>` (then [id] is `null` and [domainNameList] empty).
 */
data class StatusPageConfig(
    val id: Long? = null,
    val slug: String,
    val title: String,
    /** Markdown in Kuma; shown as plain text. */
    val description: String? = null,
    /** `/icon.svg`, `/upload/logo1.png?t=...` or an external URL; empty for a freshly created page. */
    val icon: String? = null,
    val theme: StatusPageTheme = StatusPageTheme.AUTO,
    /** Seconds; the web UI enforces at least 5. */
    val autoRefreshInterval: Int = DEFAULT_REFRESH_SECONDS,
    val published: Boolean = true,
    val showTags: Boolean = false,
    val domainNameList: List<String> = emptyList(),
    val customCss: String? = null,
    val footerText: String? = null,
    val showPoweredBy: Boolean = true,
    val analyticsType: StatusPageAnalytics? = null,
    val analyticsId: String? = null,
    val analyticsScriptUrl: String? = null,
    val showCertificateExpiry: Boolean = false,
    val showOnlyLastHeartbeat: Boolean = false,
    val rssTitle: String? = null,
) {
    companion object {
        const val DEFAULT_REFRESH_SECONDS = 300
        const val MIN_REFRESH_SECONDS = 5
    }
}

enum class StatusPageTheme(
    val key: String,
) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromKey(key: String?): StatusPageTheme = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Analytics providers accepted by `saveStatusPage` (anything else is rejected with "Invalid analytics type"). */
enum class StatusPageAnalytics(
    val key: String,
) {
    GOOGLE("google"),
    UMAMI("umami"),
    PLAUSIBLE("plausible"),
    MATOMO("matomo"),
    RYBBIT("rybbit"),
    ;

    /** Google Analytics only needs an id; all others also take a script URL. */
    val usesScriptUrl: Boolean get() = this != GOOGLE

    companion object {
        fun fromKey(key: String?): StatusPageAnalytics? = entries.firstOrNull { it.key == key }
    }
}

/** A public group of a status page; order in the list = display order (`weight`). */
data class StatusPageGroup(
    /** `null` for a group that was not saved yet. */
    val id: Long? = null,
    val name: String,
    val monitors: List<StatusPageMonitor> = emptyList(),
)

/** A monitor on a status page (`Monitor.toPublicJSON`). */
data class StatusPageMonitor(
    val id: Long,
    val name: String,
    val type: String? = null,
    /** "Show clickable link" (`sendUrl`). */
    val sendUrl: Boolean = false,
    /** Link target (custom URL or the monitor URL); Kuma only exposes it while [sendUrl] is set. */
    val url: String? = null,
    val tags: List<MonitorTag> = emptyList(),
    val certExpiryDaysRemaining: Int? = null,
    val validCert: Boolean? = null,
) {
    /** Same rule as the web status page: link only with sendUrl and a real URL. */
    val clickableUrl: String?
        get() = url?.takeIf { sendUrl && it.isNotBlank() && it != "https://" }
}

/** Incident colours of the web UI (Bootstrap contextual classes). */
enum class IncidentStyle(
    val key: String,
) {
    INFO("info"),
    WARNING("warning"),
    DANGER("danger"),
    PRIMARY("primary"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** The web UI's default for new incidents. */
        val DEFAULT = PRIMARY

        fun fromKey(key: String?): IncidentStyle = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** `IncidentPublic`. Times are UTC epoch millis. */
data class Incident(
    val id: Long,
    val title: String,
    /** Markdown in Kuma; shown as plain text. */
    val content: String? = null,
    val style: IncidentStyle = IncidentStyle.DEFAULT,
    val pin: Boolean = true,
    val active: Boolean = true,
    val createdMillis: Long? = null,
    val lastUpdatedMillis: Long? = null,
)

/** What the incident editor posts (`postIncident`). [id] set = update that incident. */
data class IncidentDraft(
    val title: String,
    val content: String,
    val style: IncidentStyle = IncidentStyle.DEFAULT,
    val id: Long? = null,
)

/** One page of the incident history (newest first, 10 per page). */
data class IncidentHistoryPage(
    val incidents: List<Incident>,
    val total: Int,
    /** Pass to the next request; `null` when [hasMore] is false. */
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** A maintenance window shown on a status page (only those currently under maintenance). */
data class StatusPageMaintenance(
    val id: Long,
    val title: String,
    val description: String? = null,
    /** `under-maintenance`, `scheduled`, ... */
    val status: String? = null,
)

/** `GET /api/status-page/<slug>`: everything except heartbeats. */
data class PublicStatusPage(
    val config: StatusPageConfig,
    /** Pinned and active incidents (Kuma shows the first). */
    val incidents: List<Incident> = emptyList(),
    val groups: List<StatusPageGroup> = emptyList(),
    val maintenances: List<StatusPageMaintenance> = emptyList(),
) {
    val incident: Incident? get() = incidents.firstOrNull()
}

/** `GET /api/status-page/heartbeat/<slug>`: beats (oldest first, max 100) and 24 h uptime (0..1) per monitor. */
data class StatusPageHeartbeats(
    val beats: Map<Long, List<Heartbeat>> = emptyMap(),
    val uptime24h: Map<Long, Double> = emptyMap(),
) {
    fun lastBeat(monitorId: Long): Heartbeat? = beats[monitorId]?.lastOrNull()

    /** Overall status like the web status page: from the latest beat of every monitor with beats. */
    val overallStatus: OverallStatus
        get() = OverallStatus.of(beats.values.mapNotNull { it.lastOrNull()?.status })
}

/** `STATUS_PAGE_*` constants of Kuma plus [UNKNOWN] (no heartbeats yet). */
enum class OverallStatus {
    ALL_UP,
    PARTIAL_DOWN,
    ALL_DOWN,
    MAINTENANCE,
    UNKNOWN,
    ;

    companion object {
        /** `StatusPage.vue` `overallStatus`: any maintenance wins; no UP at all = all down. */
        fun of(lastStatuses: Collection<MonitorStatus>): OverallStatus {
            if (lastStatuses.isEmpty()) return UNKNOWN
            if (MonitorStatus.MAINTENANCE in lastStatuses) return MAINTENANCE
            val ups = lastStatuses.count { it == MonitorStatus.UP }
            return when (ups) {
                lastStatuses.size -> ALL_UP
                0 -> ALL_DOWN
                else -> PARTIAL_DOWN
            }
        }
    }
}

/** A public status page address: server base URL (may contain a sub-path) plus slug. */
data class StatusPageAddress(
    val baseUrl: String,
    val slug: String,
) {
    val webUrl: String get() = "${baseUrl.trimEnd('/')}/status/$slug"

    companion object {
        private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private const val STATUS_SEGMENT = "status"
        private const val RSS_SEGMENT = "rss"

        /**
         * Parses a status page link such as `https://host/status/my-page` or `host/kuma/status/my-page?x`
         * (sub-path kept in [baseUrl], `https://` added when missing). `.../status` alone is the
         * `default` page. `null` when the link has no `/status/` part, no host or an invalid slug.
         */
        @Suppress("ReturnCount")
        fun parse(input: String): StatusPageAddress? {
            var url = input.trim()
            if (url.isEmpty()) return null
            if (!scheme.containsMatchIn(url)) url = "https://$url"
            url = url.substringBefore('#').substringBefore('?')
            val schemeName = url.substringBefore("://").lowercase()
            if (schemeName != "http" && schemeName != "https") return null
            val rest = url.substringAfter("://")
            val host = rest.substringBefore('/')
            if (host.isBlank()) return null
            val segments = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            // ".../status/<slug>", ".../status/<slug>/rss" or ".../status" (the slug itself may be "status").
            val last = segments.size - 1
            val candidates = listOfNotNull((last - 2).takeIf { segments.lastOrNull() == RSS_SEGMENT }, last - 1, last)
            val index = candidates.firstOrNull { segments.getOrNull(it) == STATUS_SEGMENT } ?: return null
            val slug = StatusPageSlug.normalize(segments.getOrNull(index + 1) ?: StatusPageSlug.SPECIAL_DEFAULT)
            if (StatusPageSlug.validate(slug) != null) return null
            val base = "$schemeName://$host" + segments.take(index).joinToString("") { "/$it" }
            return StatusPageAddress(base, slug)
        }
    }
}

/** Slug rules of `addStatusPage` / `saveStatusPage` (`checkSlug`) and the web UI form. */
object StatusPageSlug {
    enum class Problem { EMPTY, INVALID_CHARACTERS, CONSECUTIVE_OR_EDGE_DASH }

    private val valid = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val allowedChars = Regex("^[a-z0-9-]+$")

    /** `default` is served at `/status` as well (web UI hint "statusPageSpecialSlugDesc"). */
    const val SPECIAL_DEFAULT = "default"

    /** What the server stores: trimmed and lower-cased. */
    fun normalize(input: String): String = input.trim().lowercase()

    /** `null` when [input] (after [normalize]) is a valid slug. */
    fun validate(input: String): Problem? {
        val slug = normalize(input)
        return when {
            slug.isEmpty() -> Problem.EMPTY
            !allowedChars.matches(slug) -> Problem.INVALID_CHARACTERS
            !valid.matches(slug) -> Problem.CONSECUTIVE_OR_EDGE_DASH
            else -> null
        }
    }

    /** Suggests a slug for a title: `"My Status!"` -> `"my-status"`. */
    fun fromTitle(title: String): String =
        title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
