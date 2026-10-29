package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.kumaclient.dto.CertInfoPayloadDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.HeartbeatRowDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.InfoDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.MaintenanceDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.MonitorDto
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientDouble
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientLong
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull

/** Turns the positional arguments of a server -> client event into a [KumaEvent]. */
object KumaEventParser {
    /** Every event name this parser understands (the client subscribes to exactly these). */
    val EVENTS: List<String> =
        listOf(
            "info",
            "setup",
            "loginRequired",
            "autoLogin",
            "refresh",
            "monitorList",
            "updateMonitorIntoList",
            "deleteMonitorFromList",
            "heartbeatList",
            "heartbeat",
            "uptime",
            "avgPing",
            "certInfo",
            "maintenanceList",
            "notificationList",
        ) + listOf("statusPageList", "proxyList", "dockerHostList", "remoteBrowserList", "apiKeyList") +
            CLOUDFLARED_EVENTS

    /** Cloudflare tunnel state (settings screen), delivered only as [KumaEvent.Raw]. */
    private val CLOUDFLARED_EVENTS: List<String>
        get() =
            listOf(
                "cloudflared_installed",
                "cloudflared_running",
                "cloudflared_message",
                "cloudflared_errorMessage",
                "cloudflared_token",
            )

    /**
     * List events that are additionally delivered unparsed as [KumaEvent.Raw] (full payload,
     * latest value wins). Everything a management screen needs that is pushed by the server.
     */
    val SNAPSHOT_EVENTS: Set<String> =
        setOf(
            "maintenanceList",
            "notificationList",
            "statusPageList",
            "proxyList",
            "dockerHostList",
            "remoteBrowserList",
            "apiKeyList",
            // Server administration (settings screens): full `info` (dbType, runtime, isContainer) and the
            // Cloudflare tunnel state, which have no typed event.
            "info",
        ) + CLOUDFLARED_EVENTS

    /**
     * Parses [args] of [event]. Returns `null` for unknown events or payloads that do not make
     * sense (e.g. missing ids); throws [kotlinx.serialization.SerializationException] /
     * [IllegalArgumentException] for malformed JSON so callers can log it.
     */
    @Suppress("CyclomaticComplexMethod")
    fun parse(
        event: String,
        args: List<JsonElement>,
    ): KumaEvent? {
        val first = args.getOrNull(0)
        return when (event) {
            "info" -> {
                first?.let { KumaEvent.Info(decode(InfoDto.serializer(), it)) }
            }

            "setup" -> {
                KumaEvent.Setup
            }

            "loginRequired" -> {
                KumaEvent.LoginRequired
            }

            "autoLogin" -> {
                KumaEvent.AutoLogin
            }

            "refresh" -> {
                KumaEvent.Refresh
            }

            "monitorList" -> {
                KumaEvent.MonitorList(decodeKeyed(first, MonitorDto.serializer()))
            }

            "updateMonitorIntoList" -> {
                KumaEvent.MonitorUpdate(decodeKeyed(first, MonitorDto.serializer()))
            }

            "deleteMonitorFromList" -> {
                first.asLenientLong()?.let { KumaEvent.MonitorDeleted(it) }
            }

            "heartbeatList" -> {
                first.asLenientLong()?.let { id ->
                    val rows = args.getOrNull(1)?.let { decode(ListSerializer(HeartbeatRowDto.serializer()), it) }
                    KumaEvent.HeartbeatList(id, rows.orEmpty(), args.getOrNull(2).asLenientBoolean() == true)
                }
            }

            "heartbeat" -> {
                first?.let { KumaEvent.NewHeartbeat(decode(HeartbeatDto.serializer(), it)) }
            }

            "uptime" -> {
                parseUptime(args)
            }

            "avgPing" -> {
                first.asLenientLong()?.let { KumaEvent.AvgPing(it, args.getOrNull(1).asLenientDouble()) }
            }

            "certInfo" -> {
                parseCertInfo(args)
            }

            "maintenanceList" -> {
                KumaEvent.MaintenanceList(decodeKeyed(first, MaintenanceDto.serializer()))
            }

            "notificationList" -> {
                KumaEvent.NotificationList(
                    first?.let { decode(ListSerializer(NotificationDto.serializer()), it) }.orEmpty(),
                )
            }

            else -> {
                null
            }
        }
    }

    /** Parses the double-encoded `certInfo` JSON string. */
    fun parseCertPayload(json: String): CertInfoPayloadDto =
        KumaJson.json.decodeFromString(CertInfoPayloadDto.serializer(), json)

    private fun parseUptime(args: List<JsonElement>): KumaEvent.Uptime? {
        val id = args.getOrNull(0).asLenientLong() ?: return null
        val period = UptimePeriod.fromKey(args.getOrNull(1).asStringOrNull()) ?: return null
        val fraction = args.getOrNull(2).asLenientDouble() ?: return null
        return KumaEvent.Uptime(id, period, fraction)
    }

    private fun parseCertInfo(args: List<JsonElement>): KumaEvent.Cert? {
        val id = args.getOrNull(0).asLenientLong() ?: return null
        val payload =
            when (val raw = args.getOrNull(1)) {
                is JsonObject -> decode(CertInfoPayloadDto.serializer(), raw)
                else -> raw.asStringOrNull()?.let { parseCertPayload(it) }
            } ?: return null
        return KumaEvent.Cert(id, payload)
    }

    private fun <T> decode(
        strategy: DeserializationStrategy<T>,
        element: JsonElement,
    ): T = KumaJson.json.decodeFromJsonElement(strategy, element)

    /** Objects keyed by (string) id. Entries that fail to parse are skipped, not fatal. */
    private fun <T> decodeKeyed(
        element: JsonElement?,
        strategy: DeserializationStrategy<T>,
    ): Map<Long, T> {
        val obj = element as? JsonObject ?: return emptyMap()
        return buildMap {
            obj.forEach { (key, value) ->
                val id = key.toLongOrNull() ?: return@forEach
                runCatching { decode(strategy, value) }.getOrNull()?.let { put(id, it) }
            }
        }
    }
}
