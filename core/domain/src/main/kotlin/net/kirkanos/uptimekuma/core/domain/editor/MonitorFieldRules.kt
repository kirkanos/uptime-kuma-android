package net.kirkanos.uptimekuma.core.domain.editor

/**
 * Visibility conditions of the monitor form. The spec's `visible_if` entries are prose
 * (e.g. `type != 'globalping' || subtype == 'http'`), so they are translated here by hand; a unit test
 * checks that every field with a `visible_if` has a rule.
 */
object MonitorFieldRules {
    val HTTP_TYPES = setOf("http", "keyword", "json-query")
    private const val GLOBALPING = "globalping"

    /** Field names whose `visible_if` is covered by [rules] (or deliberately not, see [UNCONDITIONAL]). */
    val ruleNames: Set<String> get() = rules.keys + UNCONDITIONAL

    /** `conditions` depends on the type's condition variables and is checked separately. */
    private val UNCONDITIONAL = setOf("conditions")

    private fun V.gp(subtype: String) = type == GLOBALPING && str("subtype") == subtype

    private fun V.gpHttpCheck(check: String) = gp("http") && str("responsecheck") == check

    private val rules: Map<String, V.() -> Boolean> =
        buildMap {
            val urlLike: V.() -> Boolean = { type != GLOBALPING || str("subtype") == "http" }
            put("url", urlLike)
            put("headers", urlLike)
            put("method", { type in HTTP_TYPES || gp("http") })
            put("keyword", { type in setOf("keyword", "grpc-keyword") || gp("dns") || gpHttpCheck("keyword") })
            put("invertKeyword", { type in setOf("keyword", "grpc-keyword") || gpHttpCheck("keyword") })
            put("hostname", { type != GLOBALPING || str("subtype") in setOf("ping", "dns") })
            put(
                "dns_resolve_server",
                { type == "dns" || (type == GLOBALPING && str("subtype") in setOf("http", "dns")) },
            )
            put("port", {
                type != GLOBALPING || gp("dns") || (gp("ping") && str("protocol") == "TCP")
            })
            put("dns_resolve_type", { type == "dns" || gp("dns") })
            put("snmpV3Username", { str("snmpVersion") == "3" })
            put("sshPassword", { str("sshAuthMethod") != "privateKey" })
            put("sshPrivateKey", { str("sshAuthMethod") == "privateKey" })
            put("sshPassphrase", { str("sshAuthMethod") == "privateKey" })
            val jsonQuery: V.() -> Boolean = {
                type in setOf("json-query", "snmp", "mongodb") ||
                    (type == "mqtt" && str("mqttCheckType") == "json-query") ||
                    gpHttpCheck("json-query")
            }
            put("jsonPath", jsonQuery)
            put("expectedValue", jsonQuery)
            put("jsonPathOperator", { type in setOf("json-query", "snmp") || gpHttpCheck("json-query") })
            put("mqttWebsocketPath", { str("hostname").orEmpty().matches(Regex("(mqtts?|wss?)://.+")) })
            put("mqttSuccessMessage", { str("mqttCheckType") != "json-query" })
            val basicAuth: V.() -> Boolean = {
                type == "oracledb" ||
                    (type in HTTP_TYPES && str("authMethod") in setOf("basic", "ntlm")) ||
                    (type == "websocket-upgrade" && str("authMethod") == "basic") ||
                    (gp("http") && str("authMethod") == "basic")
            }
            put("basic_auth_user", basicAuth)
            put("basic_auth_pass", basicAuth)
            put("retryInterval", { num("maxretries") > 0 })
            put("retryOnlyOnStatusCodeFailure", { num("maxretries") > 0 })
            put("expiryNotification", { type in HTTP_TYPES || gp("http") })
            put("ignoreTls", { type in HTTP_TYPES || type == "redis" || gp("http") })
            put("cacheBust", { type in HTTP_TYPES || gp("http") })
            put("ping_count", { type == "ping" || gp("ping") })
            put("accepted_statuscodes", {
                type in HTTP_TYPES || type == "grpc-keyword" || type == "websocket-upgrade" || gp("http")
            })
            put("saveResponse", { bool("saveErrorResponse") })
            put("responseMaxLength", { bool("saveResponse") || bool("saveErrorResponse") })
            put("responsecheck", { gp("http") })
            put("authMethod", { type != GLOBALPING || gp("http") })
            put("bearer_token", { str("authMethod") == "bearer" })
            val oauth: V.() -> Boolean = { str("authMethod") == "oauth2-cc" }
            listOf(
                "oauth_auth_method",
                "oauth_token_url",
                "oauth_client_id",
                "oauth_client_secret",
                "oauth_scopes",
                "oauth_audience",
            ).forEach { put(it, oauth) }
            val mtls: V.() -> Boolean = { str("authMethod") == "mtls" }
            listOf("tlsCert", "tlsKey", "tlsCa").forEach { put(it, mtls) }
            val ntlm: V.() -> Boolean = { str("authMethod") == "ntlm" }
            put("authDomain", ntlm)
            put("authWorkstation", ntlm)
        }

    /** Form fields of [values]' type that are currently visible, in form order. */
    fun visibleFields(
        spec: MonitorSpec,
        values: Map<String, Any?>,
    ): List<FieldSpec> {
        val v = V(values)
        val typeSpec = spec.type(v.type) ?: return emptyList()
        val names = typeSpec.fields.toMutableList()
        // Frontend-only selector of the globalping HTTP check, not listed in the type's field order.
        if (v.type == GLOBALPING) names.add(names.indexOf("url").coerceAtLeast(0) + 1, "responsecheck")
        return names.mapNotNull { spec.field(it) }.filter { isVisible(it, typeSpec, v) }
    }

    fun isVisible(
        spec: MonitorSpec,
        field: String,
        values: Map<String, Any?>,
    ): Boolean = visibleFields(spec, values).any { it.name == field }

    private fun isVisible(
        field: FieldSpec,
        type: MonitorTypeSpec,
        v: V,
    ): Boolean =
        when {
            field.kind == FieldKind.HIDDEN -> false
            field.name == "conditions" -> type.supportsConditions
            else -> rules[field.name]?.invoke(v) ?: true
        }

    /**
     * Whether a visible field must be filled. The spec's `required` flag is per field; a few fields are
     * only required for some types (noted in the spec's `visible_if`/`notes`).
     */
    fun isRequired(
        field: FieldSpec,
        type: String,
    ): Boolean =
        when (field.name) {
            "name", "pushToken", "conditions", "notificationIDList", "subtype" -> false
            "keyword" -> type in setOf("keyword", "grpc-keyword")
            "jsonPath", "expectedValue" -> type != "mongodb"
            "dns_resolve_server" -> type == "dns"
            "radiusPassword" -> type in setOf("snmp", "radius")
            "basic_auth_user" -> type == "oracledb"
            else -> field.required && field.kind != FieldKind.CHECKBOX
        }

    /** Typed read access to form values for the rules. */
    class V(
        private val values: Map<String, Any?>,
    ) {
        val type: String get() = values["type"] as? String ?: "http"

        fun str(key: String): String? = values[key]?.toString()

        fun num(key: String): Double = values[key].asDouble() ?: 0.0

        fun bool(key: String): Boolean = values[key].asBoolean()
    }
}

/** Lenient number view of a form value (number or numeric text). */
fun Any?.asDouble(): Double? =
    when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }

/** Lenient boolean view (`true`, `1`, `"true"`). */
fun Any?.asBoolean(): Boolean =
    when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true) || this == "1"
        else -> false
    }

/** Lenient id view (number or numeric text), `null` for blank/invalid. */
fun Any?.asId(): Long? =
    when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull()
        else -> null
    }
