package net.kirkanos.uptimekuma.core.kumaclient

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Time conversions for the formats Kuma sends. */
object KumaTime {
    private val nodeCertDate = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy 'GMT'", Locale.US)

    /**
     * Parses Kuma's zone-less UTC timestamps (`"YYYY-MM-DD HH:mm:ss.SSS"`, also without millis)
     * to epoch millis. ISO strings with an explicit zone/offset are accepted too. `null` if unparseable.
     */
    fun parseUtcMillis(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val iso = text.replace(' ', 'T')
        return try {
            LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Parses Node's certificate dates (`"Sep 26 22:49:11 2026 GMT"`, day may be space padded). */
    fun parseCertDateMillis(value: String?): Long? {
        val text = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(text, nodeCertDate).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            parseUtcMillis(text)
        }
    }

    /** Chart bucket timestamps are unix seconds. */
    fun unixSecondsToMillis(seconds: Long): Long = seconds * MILLIS_PER_SECOND

    private const val MILLIS_PER_SECOND = 1000L
}
