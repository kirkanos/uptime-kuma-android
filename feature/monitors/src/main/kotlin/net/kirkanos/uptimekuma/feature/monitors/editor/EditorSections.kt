package net.kirkanos.uptimekuma.feature.monitors.editor

import net.kirkanos.uptimekuma.core.domain.editor.FieldSpec
import net.kirkanos.uptimekuma.core.domain.editor.MonitorFieldRules
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec

/** Form sections, in screen order. [TYPE] holds the type-specific fields (target, checks, credentials). */
enum class SectionKind { GENERAL, TYPE, MONITORING, HTTP_REQUEST, AUTH, ADVANCED, PROXY, NOTIFICATIONS, TAGS }

data class EditorSection(
    val kind: SectionKind,
    val fields: List<FieldSpec>,
)

/** Groups the visible fields of a draft into [EditorSection]s. */
object EditorSections {
    private val general = setOf("name", "parent", "description")
    private val monitoring =
        setOf("interval", "maxretries", "retryInterval", "retryOnlyOnStatusCodeFailure", "timeout", "resendInterval")
    private val httpRequest = setOf("method", "httpBodyEncoding", "body", "headers")
    private val auth =
        setOf(
            "authMethod",
            "basic_auth_user",
            "basic_auth_pass",
            "bearer_token",
            "oauth_auth_method",
            "oauth_token_url",
            "oauth_client_id",
            "oauth_client_secret",
            "oauth_scopes",
            "oauth_audience",
            "tlsCert",
            "tlsKey",
            "tlsCa",
            "authDomain",
            "authWorkstation",
        )
    private val advanced =
        setOf(
            "expiryNotification",
            "domainExpiryNotification",
            "ignoreTls",
            "cacheBust",
            "upsideDown",
            "accepted_statuscodes",
            "maxredirects",
            "ipFamily",
            "saveErrorResponse",
            "saveResponse",
            "responseMaxLength",
            "wsIgnoreSecWebsocketAcceptHeader",
        )

    fun sectionOf(
        field: String,
        type: String,
    ): SectionKind =
        when {
            field in general -> SectionKind.GENERAL

            field in monitoring -> SectionKind.MONITORING

            field in httpRequest && type != "globalping" -> SectionKind.HTTP_REQUEST

            // Oracle uses basic_auth_* as database credentials.
            field in auth && type != "oracledb" -> SectionKind.AUTH

            field in advanced -> SectionKind.ADVANCED

            field == "proxyId" -> SectionKind.PROXY

            field == "notificationIDList" -> SectionKind.NOTIFICATIONS

            field == "tags" -> SectionKind.TAGS

            else -> SectionKind.TYPE
        }

    /** Visible sections of [values]; notifications and tags are always present. */
    fun build(
        spec: MonitorSpec,
        values: Map<String, Any?>,
    ): List<EditorSection> {
        val type = values["type"] as? String ?: "http"
        val byKind = MonitorFieldRules.visibleFields(spec, values).groupBy { sectionOf(it.name, type) }
        return SectionKind.entries.mapNotNull { kind ->
            val fields = byKind[kind].orEmpty()
            when {
                kind == SectionKind.TAGS -> EditorSection(kind, emptyList())
                kind == SectionKind.NOTIFICATIONS -> EditorSection(kind, fields)
                fields.isEmpty() -> null
                else -> EditorSection(kind, fields)
            }
        }
    }
}
