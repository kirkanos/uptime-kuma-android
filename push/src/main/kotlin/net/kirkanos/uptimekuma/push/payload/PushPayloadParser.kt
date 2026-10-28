package net.kirkanos.uptimekuma.push.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.kirkanos.uptimekuma.core.model.MonitorStatus
import net.kirkanos.uptimekuma.core.model.PushEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

/**
 * Parses what arrives in `PushService.onMessage` (docs/push/README.md section 3).
 *
 * Never throws. Handles, in order:
 * 1. complete JSON objects of schema v1 (and, best effort, any object with `monitor`/`heartbeat`/`msg`,
 *    which also covers Kuma's stock `json` webhook body and future schema versions);
 * 2. JSON cut off by ntfy's silent 4096-byte truncation (fields are extracted with regular expressions);
 * 3. anything else, which becomes the plain-text [PushEvent.message].
 *
 * A missing monitor id means Kuma's test notification (or an unknown sender): [PushEvent.status] is then `null`.
 */
object PushPayloadParser {
    private val json = Json

    private const val ELLIPSIS = "…"

    /** Kuma's `heartbeat.time`: UTC, `YYYY-MM-DD HH:mm:ss.SSS` (fraction optional). */
    private val kumaTimeFormatter: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter()

    fun parse(bytes: ByteArray): PushEvent = parse(bytes.decodeToString())

    fun parse(text: String): PushEvent {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PushEvent(monitorId = null, monitorName = null, status = null, message = "")
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        return when {
            element is JsonObject -> fromJson(element, trimmed)
            element is JsonPrimitive && element.isString -> plain(element.content)
            trimmed.startsWith("{") -> fromTruncated(trimmed)
            else -> plain(trimmed)
        }
    }

    /** Parses Kuma's UTC heartbeat time; also accepts ISO-8601 instants. `null` if unparseable. */
    fun parseKumaTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(value.trim(), kumaTimeFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            runCatching { Instant.parse(value.trim()).toEpochMilli() }.getOrNull()
        }
    }

    private fun plain(text: String) = PushEvent(monitorId = null, monitorName = null, status = null, message = text)

    private fun fromJson(
        root: JsonObject,
        raw: String,
    ): PushEvent {
        val monitor = root["monitor"] as? JsonObject
        val heartbeat = root["heartbeat"] as? JsonObject
        val topMsg = root["msg"].string()
        if (monitor == null && heartbeat == null && topMsg == null) return plain(raw)

        val monitorId = monitor?.get("id")?.long()
        val heartbeatMsg = heartbeat?.get("msg").string()?.takeIf { it.isNotBlank() }
        return PushEvent(
            monitorId = monitorId,
            monitorName = monitor?.get("name").string(),
            monitorType = monitor?.get("type").string(),
            target =
                target(
                    url = monitor?.get("url").string(),
                    hostname = monitor?.get("hostname").string(),
                    port = monitor?.get("port")?.long(),
                ),
            status = if (monitorId == null) null else heartbeat?.get("status")?.int()?.let(MonitorStatus::fromCode),
            message = heartbeatMsg ?: topMsg.orEmpty(),
            timeMillis = parseKumaTime(heartbeat?.get("time").string()),
            pingMs = heartbeat?.get("ping")?.double(),
            important = heartbeat?.get("important")?.bool() ?: true,
        )
    }

    private fun fromTruncated(raw: String): PushEvent {
        val split = raw.indexOf("\"heartbeat\"")
        val monitorPart = if (split >= 0) raw.substring(0, split) else raw
        val heartbeatPart = if (split >= 0) raw.substring(split) else ""

        val monitorId = number(monitorPart, "id")?.toLongOrNull()
        val heartbeatMsg =
            completeString(heartbeatPart, "msg")
                ?: partialString(heartbeatPart, "msg")?.let { it + ELLIPSIS }
        val topMsg =
            if (split < 0) completeString(raw, "msg") ?: partialString(raw, "msg")?.let { it + ELLIPSIS } else null
        val message = (heartbeatMsg ?: topMsg)?.takeIf { it.isNotBlank() }
        if (monitorId == null && message == null) return plain(raw)

        return PushEvent(
            monitorId = monitorId,
            monitorName = completeString(monitorPart, "name"),
            monitorType = completeString(monitorPart, "type"),
            target =
                target(
                    url = completeString(monitorPart, "url"),
                    hostname = completeString(monitorPart, "hostname"),
                    port = number(monitorPart, "port")?.toLongOrNull(),
                ),
            status =
                if (monitorId == null) {
                    null
                } else {
                    number(heartbeatPart, "status")?.toIntOrNull()?.let(MonitorStatus::fromCode)
                },
            message = message.orEmpty(),
            timeMillis = parseKumaTime(completeString(heartbeatPart, "time")),
            pingMs = number(heartbeatPart, "ping")?.toDoubleOrNull(),
            important = Regex("\"important\"\\s*:\\s*(true|false)").find(heartbeatPart)?.groupValues?.get(1) != "false",
        )
    }

    /** `url` for HTTP-ish monitors; Kuma stores `"https://"` as the empty default, so fall back to host[:port]. */
    private fun target(
        url: String?,
        hostname: String?,
        port: Long?,
    ): String? {
        val usableUrl = url?.trim()?.takeIf { it.isNotEmpty() && it != "https://" && it != "http://" }
        if (usableUrl != null) return usableUrl
        val host = hostname?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (port != null && port > 0) "$host:$port" else host
    }

    private fun number(
        text: String,
        key: String,
    ): String? = Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)

    private fun completeString(
        text: String,
        key: String,
    ): String? = rawString(text, key)?.takeIf { it.complete }?.raw?.let(::unescape)

    private fun partialString(
        text: String,
        key: String,
    ): String? =
        rawString(text, key)
            ?.takeIf { !it.complete }
            ?.raw
            ?.replace(Regex("(\\\\u[0-9a-fA-F]{0,3}|\\\\)$"), "")
            ?.let(::unescape)

    private class RawString(
        val raw: String,
        val complete: Boolean,
    )

    /**
     * Finds the first `"key": "..."` and returns the still-escaped string content. A hand-written scanner
     * instead of a regex: alternations like `(?:[^"\\]|\\.)*` recurse per character in java.util.regex and
     * overflow the stack on multi-kilobyte values.
     */
    private fun rawString(
        text: String,
        key: String,
    ): RawString? {
        val start =
            Regex("\"$key\"\\s*:\\s*\"")
                .find(text)
                ?.range
                ?.last
                ?.plus(1) ?: return null
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return RawString(text.substring(start, i), complete = true)
                else -> i++
            }
        }
        return RawString(text.substring(start, minOf(i, text.length)), complete = false)
    }

    private fun unescape(raw: String): String =
        runCatching { json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrDefault(raw)

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
