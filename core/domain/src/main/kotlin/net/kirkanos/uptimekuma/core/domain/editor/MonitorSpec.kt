package net.kirkanos.uptimekuma.core.domain.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Parsed `spec/monitor-types.json` (Uptime Kuma 2.5.8): monitor types, the field catalogue with
 * defaults/required flags/options, the `add` defaults and the `editMonitor` field list.
 * Load the bundled copy with [MonitorSpec.bundled].
 */
data class MonitorSpec(
    val kumaVersion: String,
    /** Types in the order of the web UI's type select. */
    val types: List<MonitorTypeSpec>,
    val fields: Map<String, FieldSpec>,
    /** `monitor_defaults_new`: what the web UI starts a new monitor with. */
    val defaultsNew: Map<String, Any?>,
    /** Read-only `MonitorJSON` keys that must never be sent with `add`. */
    val readonlyKeys: Set<String>,
    /** Keys `editMonitor` copies into the DB. */
    val editCopiedKeys: Set<String>,
    val stringOperators: List<String>,
    val numberOperators: List<String>,
) {
    private val typeById = types.associateBy { it.id }

    fun type(id: String): MonitorTypeSpec? = typeById[id]

    fun field(name: String): FieldSpec? = fields[name]

    /** Field specs of [type] in form order (unknown names skipped). */
    fun fieldsOf(type: String): List<FieldSpec> = type(type)?.fields.orEmpty().mapNotNull { fields[it] }

    companion object {
        // Absolute path: R8 repackages classes, so a lookup relative to the class breaks in release builds.
        const val RESOURCE = "net/kirkanos/uptimekuma/core/domain/editor/monitor-types.json"

        /** The spec copied from the repository's `spec/` folder at build time (classpath resource). */
        val bundled: MonitorSpec by lazy {
            val stream =
                checkNotNull(MonitorSpec::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
                    "$RESOURCE missing from the classpath"
                }
            parse(stream.bufferedReader().use { it.readText() })
        }

        fun parse(text: String): MonitorSpec {
            val root =
                checkNotNull(JsonValues.parseOrNull(text) as? JsonObject) { "monitor spec is not a JSON object" }
            val fieldList = root.getValue("field_catalogue").jsonArray.map { FieldSpec.parse(it.jsonObject) }
            val typesObj = root.getValue("types").jsonObject
            val order =
                root["types_order_in_ui"]
                    ?.jsonArray
                    ?.map { it.str() }
                    .orEmpty()
                    .ifEmpty { typesObj.keys.toList() }
            val operators = root["conditions"]?.jsonObject?.get("operators")?.jsonObject
            return MonitorSpec(
                kumaVersion = root["version"].str(),
                types = order.mapNotNull { id -> typesObj[id]?.jsonObject?.let { MonitorTypeSpec.parse(id, it) } },
                fields = fieldList.associateBy { it.name },
                defaultsNew = (JsonValues.toKotlin(root["monitor_defaults_new"]) as? Map<*, *>).stringKeys(),
                readonlyKeys = root.strings("readonly_in_monitor_json_do_not_send_on_add").toSet(),
                editCopiedKeys = root.strings("edit_monitor_copied_fields").toSet(),
                stringOperators =
                    operators
                        ?.get("string")
                        ?.jsonArray
                        ?.map { it.str() }
                        .orEmpty(),
                numberOperators =
                    operators
                        ?.get("number")
                        ?.jsonArray
                        ?.map { it.str() }
                        .orEmpty(),
            )
        }
    }
}

/** One monitor type (`types.<id>`). */
data class MonitorTypeSpec(
    val id: String,
    val label: String,
    /** Raw optgroup text, e.g. "General Monitor Type"; see [group]. */
    val optgroup: String,
    /** Field names shown for this type, in form order. */
    val fields: List<String>,
    val conditionVariables: List<ConditionVariable> = emptyList(),
    /** Free-text availability condition (e.g. "!info.isContainer"), `null` = always available. */
    val availableIf: String? = null,
) {
    val group: TypeGroup
        get() =
            when {
                optgroup.startsWith("General") -> TypeGroup.GENERAL
                optgroup.startsWith("Passive") -> TypeGroup.PASSIVE
                optgroup.startsWith("Specific") -> TypeGroup.SPECIFIC
                optgroup.startsWith("Database") -> TypeGroup.DATABASE
                optgroup.startsWith("Game") -> TypeGroup.GAME_SERVER
                else -> TypeGroup.OTHER
            }

    val supportsConditions: Boolean get() = conditionVariables.isNotEmpty()

    companion object {
        internal fun parse(
            id: String,
            obj: JsonObject,
        ) = MonitorTypeSpec(
            id = id,
            label = obj["label"].str().ifEmpty { id },
            optgroup = obj["optgroup"].str(),
            fields = obj.strings("fields"),
            conditionVariables =
                obj["condition_variables"]
                    ?.jsonArray
                    ?.map {
                        val v = it.jsonObject
                        ConditionVariable(v["id"].str(), v["operators"].str())
                    }.orEmpty(),
            availableIf = (obj["available_if"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/** Type picker groups, in the order the web UI shows its optgroups. */
enum class TypeGroup { GENERAL, SPECIFIC, DATABASE, GAME_SERVER, PASSIVE, OTHER }

/** A variable usable in monitor conditions; [operators] is `string`, `number` or `string+number`. */
data class ConditionVariable(
    val id: String,
    val operators: String,
) {
    fun operatorIds(spec: MonitorSpec): List<String> =
        buildList {
            if ("string" in operators) addAll(spec.stringOperators)
            if ("number" in operators) addAll(spec.numberOperators)
        }
}

/** One entry of `field_catalogue`. */
data class FieldSpec(
    val name: String,
    /** Raw spec type, e.g. `text`, `number`, `select(dockerHostList.id)`, `textarea(json)`. */
    val rawType: String,
    /** `null` = every type. */
    val visibleFor: Set<String>?,
    val required: Boolean,
    val hasDefault: Boolean,
    val default: Any?,
    /** List options, or options per type key (e.g. `http/keyword/json-query`, `globalping(http)`). */
    val options: Any?,
    val dbColumn: String?,
    val min: Double? = null,
    val max: Double? = null,
    val pattern: String? = null,
    val placeholder: String? = null,
    val visibleIf: String? = null,
    val sensitive: Boolean = false,
    val frontendOnly: Boolean = false,
) {
    val kind: FieldKind get() = FieldKind.of(name, rawType)

    fun isFor(type: String): Boolean = visibleFor == null || type in visibleFor

    /** Options for [type] (and globalping [subtype]); empty when the field has none. */
    fun optionsFor(
        type: String,
        subtype: String? = null,
    ): List<Any?> =
        when (val o = options) {
            is List<*> -> {
                o
            }

            is Map<*, *> -> {
                val keys = o.keys.map { it.toString() }
                val key =
                    keys.firstOrNull { subtype != null && it == "$type($subtype)" }
                        ?: keys.firstOrNull { k -> !k.contains('(') && type in k.split('/') }
                        ?: keys.firstOrNull { subtype != null && it == subtype }
                (key?.let { o[it] } as? List<*>).orEmpty()
            }

            else -> {
                emptyList()
            }
        }

    companion object {
        internal fun parse(obj: JsonObject): FieldSpec {
            val visible = obj["visible_for"]
            return FieldSpec(
                name = obj["name"].str(),
                rawType = obj["type"].str(),
                visibleFor = (visible as? JsonArray)?.map { it.str() }?.toSet(),
                required = (obj["required"] as? JsonPrimitive)?.content == "true",
                hasDefault = obj.containsKey("default"),
                default = JsonValues.toKotlin(obj["default"]),
                options = JsonValues.toKotlin(obj["options"]),
                dbColumn = (obj["db_column"] as? JsonPrimitive)?.contentOrNull,
                min = (obj["min"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull,
                max = (obj["max"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull,
                pattern = (obj["pattern"] as? JsonPrimitive)?.contentOrNull,
                placeholder = (obj["placeholder"] as? JsonPrimitive)?.contentOrNull,
                visibleIf = (obj["visible_if"] as? JsonPrimitive)?.contentOrNull,
                sensitive = (obj["sensitive"] as? JsonPrimitive)?.content == "true",
                frontendOnly = (obj["frontend_only"] as? JsonPrimitive)?.content == "true",
            )
        }
    }
}

/** Widget family of a field, derived from the spec type (plus a few well-known names). */
enum class FieldKind {
    TEXT,
    URL,
    PASSWORD,
    NUMBER,
    CHECKBOX,
    SELECT,
    MULTILINE,
    JSON,
    HEADERS,
    STRING_LIST,
    STATUS_CODES,
    NOTIFICATIONS,
    PROXY,
    PARENT,
    DOCKER_HOST,
    REMOTE_BROWSER,
    CONDITIONS,
    KAFKA_SASL,
    TAGS,
    PUSH_TOKEN,
    HIDDEN,
    ;

    companion object {
        private val byName =
            mapOf(
                "headers" to HEADERS,
                "accepted_statuscodes" to STATUS_CODES,
                "pushToken" to PUSH_TOKEN,
                "kafkaProducerSaslOptions" to KAFKA_SASL,
                "body" to JSON,
                "grpcBody" to JSON,
                "grpcMetadata" to JSON,
                "active" to HIDDEN,
                "type" to HIDDEN,
                "expectedValue" to TEXT,
            )

        private val byType =
            mapOf(
                "text" to TEXT,
                "url" to URL,
                "password" to PASSWORD,
                "number" to NUMBER,
                "checkbox" to CHECKBOX,
                "select" to SELECT,
                "textarea(json)" to JSON,
                "textarea" to MULTILINE,
                "string[]" to STRING_LIST,
            )

        private val byTypePrefix =
            listOf(
                // The GameDig list needs a server round trip; the game id is entered as text.
                "select(getGameList" to TEXT,
                "object<" to NOTIFICATIONS,
                "radio(proxyList" to PROXY,
                "select(group" to PARENT,
                "select(dockerHostList" to DOCKER_HOST,
                "select(remoteBrowserList" to REMOTE_BROWSER,
                "Condition" to CONDITIONS,
                "MonitorTag" to TAGS,
            )

        fun of(
            name: String,
            rawType: String,
        ): FieldKind =
            byName[name]
                ?: byType[rawType]
                ?: byTypePrefix.firstOrNull { rawType.startsWith(it.first) }?.second
                ?: HIDDEN
    }
}

private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.str() }.orEmpty()

private fun Map<*, *>?.stringKeys(): Map<String, Any?> =
    this?.entries?.associate { (k, v) -> k.toString() to v }.orEmpty()
