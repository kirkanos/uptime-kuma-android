package net.kirkanos.uptimekuma.push.payload

/**
 * The LiquidJS body template rendered by Kuma's `webhook` provider (`webhookContentType: "custom"`).
 * Schema v1, see docs/push/README.md section 2.1. Every value is piped through
 * `| json | default: "null"` so that Kuma's test notification (no monitor, no heartbeat) and missing
 * optional fields still render valid JSON.
 */
object WebhookTemplate {
    const val SCHEMA_VERSION: Int = 1

    private val monitorFields = listOf("id", "name", "type", "url", "hostname", "port")
    private val heartbeatFields =
        listOf("status", "msg", "time", "localDateTime", "ping", "duration", "retries", "important")

    /** The template as a single line, exactly as stored in `webhookCustomBody`. */
    val BODY: String =
        buildString {
            append("{\"v\":").append(SCHEMA_VERSION)
            append(",\"monitor\":{")
            append(monitorFields.joinToString(",") { field(it, "monitorJSON") })
            append("},\"heartbeat\":{")
            append(heartbeatFields.joinToString(",") { field(it, "heartbeatJSON") })
            append("},\"msg\":{{ msg | json }}}")
        }

    private fun field(
        name: String,
        source: String,
    ): String = "\"$name\":{{ $source.$name | json | default: \"null\" }}"
}
