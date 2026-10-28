package net.kirkanos.uptimekuma.core.kumaclient.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** JSON configuration shared by all Kuma payload parsing. Kuma payloads are loosely typed. */
object KumaJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }
}

/** Interprets `true`/`false`, `0`/`1` and their string forms. `null` for anything else. */
fun JsonElement?.asLenientBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.booleanOrNull
        ?: primitive.longOrNull?.let { it != 0L }
        ?: primitive.doubleOrNull?.let { it != 0.0 }
}

/** Interprets a JSON number or numeric string as Long (ids arrive as both). */
fun JsonElement?.asLenientLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: primitive.content.trim().toLongOrNull()
}

/** Interprets a JSON number or numeric string as Double. */
fun JsonElement?.asLenientDouble(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull()
}

/** String content of a primitive (numbers included), `null` for JSON null / non-primitives. */
fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive is JsonNull) null else primitive.content
}

/**
 * Converts a JSON element to plain Kotlin values: String, Long (integral numbers), Double,
 * Boolean, null, List and Map.
 */
fun JsonElement.toPlainValue(): Any? =
    when (this) {
        is JsonNull -> {
            null
        }

        is JsonPrimitive -> {
            when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                else -> doubleOrNull ?: content
            }
        }

        is JsonObject -> {
            mapValues { (_, value) -> value.toPlainValue() }
        }

        is JsonArray -> {
            map { it.toPlainValue() }
        }
    }

/** Inverse of [toPlainValue]; unknown types are encoded via `toString()`. */
fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this.toLong())
        is Long -> JsonPrimitive(this)
        is Short -> JsonPrimitive(this.toLong())
        is Byte -> JsonPrimitive(this.toLong())
        is Number -> JsonPrimitive(this.toDouble())
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Array<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }
