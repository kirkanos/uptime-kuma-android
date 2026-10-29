package net.kirkanos.uptimekuma.core.domain.editor

import kotlinx.serialization.json.JsonObject
import java.net.URI

/** Why a field value is rejected. [arg] carries the limit for [Code.TOO_SMALL]/[Code.TOO_LARGE]. */
data class FieldIssue(
    val code: Code,
    val arg: Double? = null,
) {
    enum class Code {
        REQUIRED,
        NOT_A_NUMBER,
        TOO_SMALL,
        TOO_LARGE,
        INVALID_URL,
        INVALID_JSON,
        INVALID_FORMAT,
        INVALID_STATUS_CODE,
        INVALID_NODE_URL,
        NO_COMMA,
    }
}

/** Result of [MonitorValidator.validate]: blocking errors per field and non-blocking warnings. */
data class ValidationResult(
    val errors: Map<String, FieldIssue> = emptyMap(),
    /** Interval below 20 s: Kuma's web UI asks for confirmation. */
    val shortIntervalWarning: Boolean = false,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Client-side checks mirroring the web UI's `isInputValid()` and the server's `Monitor.validate()`
 * (`server/model/monitor.js:1630-1785`), so most `add`/`editMonitor` rejections are caught early.
 */
object MonitorValidator {
    const val MIN_INTERVAL_SECONDS = 1.0
    const val SHORT_INTERVAL_SECONDS = 20.0
    private const val PING_TIMEOUT_MAX = 300.0
    private const val PING_REQUEST_TIMEOUT_MAX = 60.0
    private const val SCREENSHOT_DELAY_FACTOR = 500.0
    private val statusCodePattern = Regex("^\\d{3}(-\\d{3})?$")
    private val wsCodePattern = Regex("^\\d{4}(-\\d{4})?$")
    private val serviceNamePattern = Regex("^[a-zA-Z0-9._\\-@]+$")

    fun validate(
        spec: MonitorSpec,
        values: Map<String, Any?>,
    ): ValidationResult {
        val type = values["type"] as? String ?: "http"
        val errors = linkedMapOf<String, FieldIssue>()
        for (field in MonitorFieldRules.visibleFields(spec, values)) {
            check(field, type, values)?.let { errors[field.name] = it }
        }
        // Server-side rules that apply even when the field is hidden.
        if ("retryInterval" !in errors) {
            val retry = values["retryInterval"].asDouble()
            if (retry == null || retry < MIN_INTERVAL_SECONDS) {
                errors["retryInterval"] = FieldIssue(FieldIssue.Code.TOO_SMALL, MIN_INTERVAL_SECONDS)
            }
        }
        if (values["name"].isBlankValue() && MonitorDrafts.defaultName(values) == null) {
            errors["name"] = FieldIssue(FieldIssue.Code.REQUIRED)
        }
        val interval = values["interval"].asDouble()
        return ValidationResult(
            errors = errors,
            shortIntervalWarning =
                interval != null && interval >= MIN_INTERVAL_SECONDS && interval < SHORT_INTERVAL_SECONDS,
        )
    }

    private fun check(
        field: FieldSpec,
        type: String,
        values: Map<String, Any?>,
    ): FieldIssue? {
        val value = values[field.name]
        if (value.isBlankValue()) {
            return if (MonitorFieldRules.isRequired(field, type)) FieldIssue(FieldIssue.Code.REQUIRED) else null
        }
        return when (field.kind) {
            FieldKind.NUMBER -> checkNumber(field, type, values)
            FieldKind.URL -> checkUrl(type, value.toString())
            FieldKind.JSON, FieldKind.HEADERS -> checkJson(field.name, values)
            FieldKind.STATUS_CODES -> checkStatusCodes(type, value)
            FieldKind.STRING_LIST -> checkList(field.name, value)
            else -> checkText(field, type, value.toString())
        }
    }

    private fun checkNumber(
        field: FieldSpec,
        type: String,
        values: Map<String, Any?>,
    ): FieldIssue? {
        val n = values[field.name].asDouble() ?: return FieldIssue(FieldIssue.Code.NOT_A_NUMBER)
        val interval = values["interval"].asDouble() ?: 0.0
        val (min, max) =
            when (field.name) {
                "timeout" -> if (type == "ping") 1.0 to PING_TIMEOUT_MAX else 0.0 to null
                "ping_per_request_timeout" -> 1.0 to PING_REQUEST_TIMEOUT_MAX
                "screenshot_delay" -> 0.0 to kotlin.math.floor(interval * SCREENSHOT_DELAY_FACTOR)
                else -> field.min to field.max
            }
        return when {
            min != null && n < min -> FieldIssue(FieldIssue.Code.TOO_SMALL, min)
            max != null && n > max -> FieldIssue(FieldIssue.Code.TOO_LARGE, max)
            else -> null
        }
    }

    private fun checkUrl(
        type: String,
        url: String,
    ): FieldIssue? {
        val schemes =
            when (type) {
                "websocket-upgrade" -> setOf("ws", "wss")
                "real-browser" -> null
                else -> setOf("http", "https")
            }
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val ok =
            uri?.scheme != null && !uri.host.isNullOrBlank() && (schemes == null || uri.scheme.lowercase() in schemes)
        return if (ok) null else FieldIssue(FieldIssue.Code.INVALID_URL)
    }

    private fun checkJson(
        name: String,
        values: Map<String, Any?>,
    ): FieldIssue? {
        // Only a JSON body must parse; form/xml bodies are raw text.
        if (name == "body" && (values["httpBodyEncoding"] ?: "json") != "json") return null
        val parsed = JsonValues.parseOrNull(values[name].toString())
        val ok = if (name == "headers") parsed is JsonObject else parsed != null
        return if (ok) null else FieldIssue(FieldIssue.Code.INVALID_JSON)
    }

    private fun checkStatusCodes(
        type: String,
        value: Any?,
    ): FieldIssue? {
        val pattern = if (type == "websocket-upgrade") wsCodePattern else statusCodePattern
        val codes = (value as? List<*>).orEmpty().map { it.toString().trim() }
        return when {
            codes.isEmpty() -> FieldIssue(FieldIssue.Code.REQUIRED)
            codes.any { !pattern.matches(it) } -> FieldIssue(FieldIssue.Code.INVALID_STATUS_CODE)
            else -> null
        }
    }

    private fun checkList(
        name: String,
        value: Any?,
    ): FieldIssue? {
        val items = MonitorPayloadBuilder.stringList(value)
        return when {
            name == "rabbitmqNodes" && items.isEmpty() -> {
                FieldIssue(FieldIssue.Code.REQUIRED)
            }

            name == "rabbitmqNodes" && items.any { !it.startsWith("http://") && !it.startsWith("https://") } -> {
                FieldIssue(FieldIssue.Code.INVALID_NODE_URL)
            }

            else -> {
                null
            }
        }
    }

    private fun checkText(
        field: FieldSpec,
        type: String,
        text: String,
    ): FieldIssue? =
        when {
            field.name == "databaseQuery" && type == "mongodb" && JsonValues.parseOrNull(text) == null -> {
                FieldIssue(FieldIssue.Code.INVALID_JSON)
            }

            field.name == "location" && text.contains(',') -> {
                FieldIssue(FieldIssue.Code.NO_COMMA)
            }

            field.name == "system_service_name" && type == "system-service" &&
                !serviceNamePattern.matches(
                    text.trim(),
                )
            -> {
                FieldIssue(FieldIssue.Code.INVALID_FORMAT)
            }

            field.name in setOf("snmpOid", "mqttWebsocketPath") && field.pattern != null &&
                !Regex(field.pattern).matches(text.trim()) -> {
                FieldIssue(FieldIssue.Code.INVALID_FORMAT)
            }

            else -> {
                null
            }
        }
}

/** `null`, blank text, an empty list/map. */
fun Any?.isBlankValue(): Boolean =
    when (this) {
        null -> true
        is String -> isBlank()
        is Collection<*> -> isEmpty()
        is Map<*, *> -> isEmpty()
        else -> false
    }
