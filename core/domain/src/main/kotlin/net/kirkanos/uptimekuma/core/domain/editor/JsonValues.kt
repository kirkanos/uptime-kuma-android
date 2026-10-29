package net.kirkanos.uptimekuma.core.domain.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Conversion between kotlinx JSON trees and the plain Kotlin values used in [net.kirkanos.uptimekuma.core.model.MonitorDraft]. */
object JsonValues {
    private val json = Json { prettyPrint = false }

    /** JSON -> String/Long/Double/Boolean/null/List/Map. Integral numbers become [Long]. */
    fun toKotlin(element: JsonElement?): Any? =
        when (element) {
            null, JsonNull -> null
            is JsonObject -> element.mapValues { toKotlin(it.value) }
            is JsonArray -> element.map { toKotlin(it) }
            is JsonPrimitive -> primitive(element)
        }

    private fun primitive(p: JsonPrimitive): Any? =
        when {
            p.isString -> p.content
            p.booleanOrNull != null -> p.booleanOrNull
            p.content.none { it == '.' || it == 'e' || it == 'E' } && p.longOrNull != null -> p.longOrNull
            else -> p.doubleOrNull
        }

    /** Kotlin value -> JSON. Unknown objects are written as their `toString()`. */
    fun toJson(value: Any?): JsonElement =
        when (value) {
            null -> {
                JsonNull
            }

            is JsonElement -> {
                value
            }

            is String -> {
                JsonPrimitive(value)
            }

            is Boolean -> {
                JsonPrimitive(value)
            }

            is Double -> {
                if (value % 1.0 == 0.0 &&
                    value.isFinite()
                ) {
                    JsonPrimitive(value.toLong())
                } else {
                    JsonPrimitive(value)
                }
            }

            is Float -> {
                toJson(value.toDouble())
            }

            is Number -> {
                JsonPrimitive(value.toLong())
            }

            is Map<*, *> -> {
                JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            }

            is Iterable<*> -> {
                JsonArray(value.map { toJson(it) })
            }

            else -> {
                JsonPrimitive(value.toString())
            }
        }

    /** Parses [text] as JSON, `null` when it is not valid JSON. */
    fun parseOrNull(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    fun stringify(value: Any?): String = toJson(value).toString()

    /** Re-serialises JSON text with an indent of 4 like Kuma's web UI; returns [text] unchanged when invalid. */
    fun pretty(text: String): String {
        val element = parseOrNull(text) ?: return text
        return prettyJson.encodeToString(JsonElement.serializer(), element)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val prettyJson =
        Json {
            prettyPrint = true
            prettyPrintIndent = "    "
        }
}
