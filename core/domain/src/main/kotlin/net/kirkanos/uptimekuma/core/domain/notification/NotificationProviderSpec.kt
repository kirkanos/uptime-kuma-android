package net.kirkanos.uptimekuma.core.domain.notification

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues

/**
 * Parsed `spec/notification-providers.json` (Uptime Kuma 2.5.8): the 108 provider types with label,
 * categories and form fields. Load the bundled copy with [NotificationProviderSpec.bundled].
 *
 * The spec was extracted from Kuma's Vue components with regular expressions, so a few values are
 * cleaned up here (i18n placeholders, `{{false}}` option values, JS-expression defaults).
 */
data class NotificationProviderSpec(
    val kumaVersion: String,
    /** Providers sorted by label (case-insensitive), like the web UI's select. */
    val providers: List<ProviderSpec>,
) {
    private val byId = providers.associateBy { it.id }
    private val byIdLower = providers.associateBy { it.id.lowercase() }

    /** Provider by type id; falls back to a case-insensitive match (type ids are mixed case). */
    fun provider(id: String): ProviderSpec? = byId[id] ?: byIdLower[id.lowercase()]

    /** Providers of [category] in label order. */
    fun providersIn(category: ProviderCategory): List<ProviderSpec> = providers.filter { category in it.categories }

    companion object {
        const val RESOURCE = "notification-providers.json"

        /** The spec copied from the repository's `spec/` folder at build time (classpath resource). */
        val bundled: NotificationProviderSpec by lazy {
            val stream =
                checkNotNull(NotificationProviderSpec::class.java.getResourceAsStream(RESOURCE)) {
                    "$RESOURCE missing from the classpath"
                }
            parse(stream.bufferedReader().use { it.readText() })
        }

        fun parse(text: String): NotificationProviderSpec {
            val root =
                checkNotNull(JsonValues.parseOrNull(text) as? JsonObject) {
                    "notification provider spec is not a JSON object"
                }
            val types = root.getValue("types").jsonObject
            return NotificationProviderSpec(
                kumaVersion = root["version"].str(),
                providers =
                    types
                        .map { (id, obj) -> ProviderSpec.parse(id, obj.jsonObject) }
                        .sortedBy { it.label.lowercase() },
            )
        }
    }
}

/** Optgroups of the web UI's provider select. A provider can be in several. */
enum class ProviderCategory(
    val key: String,
) {
    UNIVERSAL("universal"),
    CHAT("chatPlatforms"),
    PUSH("pushServices"),
    SMS("smsServices"),
    EMAIL("email"),
    INCIDENT("incidentManagement"),
    HOME_AUTOMATION("homeAutomation"),
    REGIONAL("regional"),
    OTHER("other"),
    ;

    companion object {
        fun of(key: String): ProviderCategory = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One provider type (`types.<id>`). */
data class ProviderSpec(
    val id: String,
    val label: String,
    val categories: List<ProviderCategory>,
    /** Form fields in the order of the Vue component. */
    val fields: List<ProviderFieldSpec>,
    /** Every `notification.<x>` the server provider reads (may include legacy fields not in the form). */
    val serverFieldsRead: Set<String> = emptySet(),
) {
    private val fieldByName = fields.associateBy { it.name }

    fun field(name: String): ProviderFieldSpec? = fieldByName[name]

    /** Label without the parenthesised explanation, e.g. `Apprise` for `Apprise (Support 50+ ...)`. */
    val shortLabel: String get() =
        label
            .substringBefore(" (")
            .substringBefore("（")
            .trim()
            .ifEmpty { label }

    /** `false` when a field cannot be filled in from a native client (e.g. Webpush's browser subscription). */
    val configurable: Boolean get() = fields.none { it.kind == ProviderFieldKind.UNSUPPORTED }

    /** Names referenced by visibility conditions that are not fields (UI-only switches). */
    val uiToggles: Set<String>
        get() =
            fields
                .flatMap { it.visibility.identifiers() }
                .filterNot { it in fieldByName }
                .toSet()

    companion object {
        internal fun parse(
            id: String,
            obj: JsonObject,
        ): ProviderSpec =
            ProviderSpec(
                id = id,
                label = obj["label"].str().ifEmpty { id },
                categories = obj.strings("categories").map(ProviderCategory::of).distinct(),
                fields = (obj["fields"] as? JsonArray).orEmpty().map { ProviderFieldSpec.parse(id, it.jsonObject) },
                serverFieldsRead = obj.strings("server_fields_read").toSet(),
            )
    }
}

/** Widget family of a provider field. */
enum class ProviderFieldKind {
    TEXT,
    PASSWORD,
    NUMBER,
    URL,
    EMAIL,
    CHECKBOX,
    SELECT,
    TEXTAREA,

    /** Single-line Liquid template. */
    TEMPLATE,

    /** Multi-line Liquid template. */
    TEMPLATE_MULTILINE,

    /** JSON object stored as string (additional headers); edited as key/value pairs. */
    JSON_OBJECT,

    /** Multiselect with free entries, stored as string array. */
    LIST,

    /** Not editable natively (e.g. a browser `PushSubscription`); the stored value is kept untouched. */
    UNSUPPORTED,
    ;

    companion object {
        private val byType =
            mapOf(
                "text" to TEXT,
                "password" to PASSWORD,
                "number" to NUMBER,
                "url" to URL,
                "email" to EMAIL,
                "checkbox" to CHECKBOX,
                "boolean" to CHECKBOX,
                "select" to SELECT,
                "radio" to SELECT,
                "textarea" to TEXTAREA,
                "text(liquid-template)" to TEMPLATE,
                "textarea(liquid-template)" to TEMPLATE_MULTILINE,
                "select(multiselect)" to LIST,
            )

        fun of(
            name: String,
            rawType: String,
        ): ProviderFieldKind =
            when {
                rawType == "textarea" && name.endsWith("AdditionalHeaders") -> JSON_OBJECT
                rawType.startsWith("multiselect") -> LIST
                else -> byType[rawType] ?: UNSUPPORTED
            }
    }
}

/** A select option; [value] is typed (`{{false}}` in the spec becomes `false`). */
data class FieldOption(
    val value: Any?,
    val label: String,
)

/** One provider form field. */
data class ProviderFieldSpec(
    val name: String,
    val rawType: String,
    val required: Boolean,
    val placeholder: String? = null,
    val options: List<FieldOption> = emptyList(),
    val visibility: Visibility = Visibility.Always,
    val hasDefault: Boolean = false,
    val default: Any? = null,
    val min: Double? = null,
    val max: Double? = null,
    val pattern: String? = null,
    /** The same input exists with another type in another branch (e.g. text or number). */
    val altTypes: List<String> = emptyList(),
) {
    val kind: ProviderFieldKind get() = ProviderFieldKind.of(name, rawType)

    companion object {
        internal fun parse(
            providerId: String,
            obj: JsonObject,
        ): ProviderFieldSpec {
            val name = obj["name"].str()
            val override = NotificationDefaults.overrides["$providerId.$name"]
            val specDefault = SpecText.default(obj["default"])
            val visibleIf = (obj["visible_if"] as? JsonPrimitive)?.contentOrNull
            val visibleIfAny = obj.strings("visible_if_any")
            return ProviderFieldSpec(
                name = name,
                rawType = obj["type"].str(),
                required = (obj["required"] as? JsonPrimitive)?.content == "true",
                placeholder = SpecText.placeholder((obj["placeholder"] as? JsonPrimitive)?.contentOrNull),
                options =
                    (obj["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                        (option as? JsonObject)?.let {
                            FieldOption(SpecText.optionValue(it["value"].str()), SpecText.label(it["label"].str()))
                        }
                    },
                visibility = Visibility.of(visibleIf, visibleIfAny),
                hasDefault = override != null || specDefault != null,
                default = override?.value ?: specDefault?.value,
                min = obj.number("min"),
                max = obj.number("max"),
                pattern = (obj["pattern"] as? JsonPrimitive)?.contentOrNull,
                altTypes = obj.strings("alt_types"),
            )
        }
    }
}

/** When a field is shown. */
sealed interface Visibility {
    fun isVisible(
        values: Map<String, Any?>,
        toggles: Map<String, Boolean> = emptyMap(),
    ): Boolean

    fun identifiers(): Set<String>

    data object Always : Visibility {
        override fun isVisible(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = true

        override fun identifiers(): Set<String> = emptySet()
    }

    /** `visible_if`: one Vue condition. */
    data class When(
        val condition: Condition,
    ) : Visibility {
        override fun isVisible(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = condition.evaluate(values, toggles)

        override fun identifiers(): Set<String> = condition.identifiers()
    }

    /** `visible_if_any`: the input appears in several `v-if`/`v-else-if` branches; shown if any matches. */
    data class AnyOf(
        val conditions: List<Condition>,
    ) : Visibility {
        override fun isVisible(
            values: Map<String, Any?>,
            toggles: Map<String, Boolean>,
        ) = conditions.any { it.evaluate(values, toggles) }

        override fun identifiers(): Set<String> = conditions.flatMap { it.identifiers() }.toSet()
    }

    companion object {
        /** Unparseable conditions degrade to [Always]: a visible extra field is better than a hidden one. */
        fun of(
            visibleIf: String?,
            visibleIfAny: List<String>,
        ): Visibility =
            when {
                visibleIfAny.isNotEmpty() -> {
                    // "else" is the last branch of a v-if chain: together the branches cover every case.
                    if ("else" in visibleIfAny) {
                        Always
                    } else {
                        val parsed = visibleIfAny.map { Condition.parseOrNull(it) }
                        if (parsed.any { it == null }) Always else AnyOf(parsed.filterNotNull())
                    }
                }

                visibleIf != null -> {
                    Condition.parseOrNull(visibleIf)?.let(::When) ?: Always
                }

                else -> {
                    Always
                }
            }
    }
}

/** Hand-curated defaults where the spec only has a JS expression or the component sets one in code. */
internal object NotificationDefaults {
    class Value(
        val value: Any?,
    )

    val overrides: Map<String, Value> =
        mapOf(
            // mounted(): `ntfyUseTemplate = hasTitle || hasMessage` -> false for a new notification.
            "ntfy.ntfyUseTemplate" to Value(false),
            "ntfy.ntfyAuthenticationMethod" to Value("none"),
            "discord.discordMessageFormat" to Value("normal"),
            "fluxer.fluxerMessageFormat" to Value("normal"),
            "Whatsapp360messenger.Whatsapp360messengerGroupIds" to Value(emptyList<String>()),
            // Server falls back to the public API; the form shows it prefilled (docs/kuma-api section 5).
            "telegram.telegramServerUrl" to Value("https://api.telegram.org"),
            "telegram.telegramTemplateParseMode" to Value("plain"),
            // Required while POST; the web UI has no preselection, the server treats unset as JSON.
            "webhook.webhookContentType" to Value("json"),
        )
}

/** Cleans texts extracted from Vue templates. */
internal object SpecText {
    private val tCall = Regex("""\${'$'}t\(\s*(['"])(.*?)\1\s*(?:,\s*\{(.*)})?\s*\)""")
    private val argPair = Regex("""(\w+)\s*:\s*['"]?([^,'"}]+)['"]?""")
    private val i18nKey = Regex("^[A-Za-z0-9]+$")
    private const val KEY_GROUP = 2
    private const val ARGS_GROUP = 3
    private val mustache = Regex("""^\{\{\s*(.*?)\s*}}$""")

    class Default(
        val value: Any?,
    )

    fun placeholder(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        return when {
            text.isEmpty() -> null

            text == "\$root.appName" -> "Uptime Kuma"

            text.startsWith("\$t(") -> translate(text)

            // An i18n key such as `headersPlaceholder` (a sample in Kuma's locale files): not displayable.
            text.endsWith("Placeholder") && i18nKey.matches(text) -> null

            else -> unescape(text)
        }
    }

    fun label(raw: String): String {
        val inner = mustache.find(raw.trim())?.groupValues?.get(1) ?: return unescape(raw.trim())
        return if (inner.startsWith("\$t(")) translate(inner) ?: inner else inner
    }

    /** `{{false}}`, `{{true}}`, `{{5}}` -> typed values; anything else stays a string. */
    fun optionValue(raw: String): Any? {
        val inner = mustache.find(raw.trim())?.groupValues?.get(1) ?: return raw
        return when {
            inner == "true" -> true
            inner == "false" -> false
            inner == "null" -> null
            inner.toLongOrNull() != null -> inner.toLong()
            inner.toDoubleOrNull() != null -> inner.toDouble()
            else -> inner.trim('\'', '"')
        }
    }

    /** Literal defaults only; JS expressions (`{"js": ...}`) are dropped, a list takes its first literal. */
    fun default(element: JsonElement?): Default? =
        when (element) {
            null -> {
                null
            }

            is JsonObject -> {
                null
            }

            is JsonArray -> {
                element.firstOrNull { it is JsonPrimitive }?.let { Default(JsonValues.toKotlin(it)) }
            }

            else -> {
                Default(JsonValues.toKotlin(element))
            }
        }

    private fun translate(call: String): String? {
        val match = tCall.find(call) ?: return null
        val key = match.groupValues[KEY_GROUP]
        val argText = match.groupValues[ARGS_GROUP]
        val args = argPair.findAll(argText).associate { it.groupValues[1] to it.groupValues[2].trim() }
        if (args.isEmpty()) return key
        var text = key
        args.forEach { (name, value) -> text = text.replace("{$name}", value) }
        return when {
            text != key -> text
            i18nKey.matches(key) -> args.values.joinToString(" ")
            else -> "$key (${args.values.joinToString(", ")})"
        }
    }

    private fun unescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
}

private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.str() }.orEmpty()

private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
