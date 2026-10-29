package net.kirkanos.uptimekuma.core.domain.editor

import net.kirkanos.uptimekuma.core.model.MonitorDraft
import net.kirkanos.uptimekuma.core.model.MonitorTag
import java.net.URI
import java.security.SecureRandom
import kotlin.math.floor

/**
 * Creating and transforming [MonitorDraft]s the way Kuma's web UI does (`src/pages/EditMonitor.vue`):
 * defaults for new monitors, defaults applied when the type changes, normalisation of a loaded
 * `MonitorJSON`, cloning, and the coupled field updates (interval -> retry interval / timeout).
 */
object MonitorDrafts {
    const val PUSH_TOKEN_LENGTH = 32
    private const val PUSH_TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val TIMEOUT_FACTOR = 0.8
    private const val DEFAULT_TIMEOUT = 48.0
    private const val NTP_INTERVAL = 300L
    private const val DEFAULT_INTERVAL = 60L
    private const val DEFAULT_DNS_SERVER = "1.1.1.1"

    /** Default port per type; also the set of "known defaults" that get replaced on a type change. */
    val PORT_DEFAULTS: Map<String, Long> =
        mapOf("dns" to 53L, "radius" to 1812L, "snmp" to 161L, "ntp" to 123L, "sftp" to 22L)
    private val KNOWN_DEFAULT_PORTS = PORT_DEFAULTS.values.toSet() + 80L

    /** Timeout defaults on type change; everything else uses 48 s. */
    val TIMEOUT_DEFAULTS: Map<String, Double> = mapOf("snmp" to 5.0, "ping" to 10.0, "kafka-producer" to 1.0)

    /** Connection string templates the web UI pre-fills for database types. */
    val CONNECTION_TEMPLATES: Map<String, String> =
        mapOf(
            "sqlserver" to
                "Server=<hostname>,<port>;Database=<your database>;User Id=<your user id>;" +
                "Password=<your password>;Encrypt=<true/false>;TrustServerCertificate=<Yes/No>;" +
                "Connection Timeout=<int>",
            "postgres" to "postgres://username:password@host:port/database",
            "mysql" to "mysql://username:password@host:port/database",
            "oracledb" to "localhost:1521/FREEPDB1",
            "redis" to "redis://user:password@host:port",
            "mongodb" to "mongodb://username:password@host:port/database",
        )

    private val random = SecureRandom()

    /** A random 32-character `[A-Za-z0-9]` push token (Kuma's `genSecret(32)`). */
    fun newPushToken(): String =
        buildString {
            repeat(
                PUSH_TOKEN_LENGTH,
            ) { append(PUSH_TOKEN_ALPHABET[random.nextInt(PUSH_TOKEN_ALPHABET.length)]) }
        }

    /** `<baseUrl>/api/push/<token>?status=up&msg=OK&ping=` as shown by the web UI. */
    fun pushUrl(
        baseUrl: String,
        token: String,
    ): String = "${baseUrl.trimEnd('/')}/api/push/$token?status=up&msg=OK&ping="

    /**
     * A new monitor: `monitor_defaults_new`, the type's defaults, [notificationIds] (the default
     * notifications) and the default proxy.
     */
    fun newDraft(
        spec: MonitorSpec,
        type: String,
        notificationIds: Collection<Long> = emptyList(),
        defaultProxyId: Long? = null,
    ): MonitorDraft {
        val base =
            spec.defaultsNew.toMutableMap().apply {
                put("notificationIDList", notificationIds.associate { it.toString() to true })
                put("proxyId", defaultProxyId)
            }
        return MonitorDraft(fields = applyType(spec, base, type, previousType = null))
    }

    /** [values] switched to [type], with the web UI's per-type defaults. */
    fun applyType(
        spec: MonitorSpec,
        values: Map<String, Any?>,
        type: String,
        previousType: String? = values["type"] as? String,
    ): Map<String, Any?> {
        val v = values.toMutableMap()
        val changed = previousType != null && previousType != type
        v["type"] = type
        if (changed) v["conditions"] = emptyList<Any?>()
        // Fields of the new type that the defaults do not cover yet get their catalogue default.
        spec.fieldsOf(type).filter { it.hasDefault && it.name !in v && !it.frontendOnly }.forEach {
            v[it.name] =
                it.default
        }
        applyTargetDefaults(v, type)
        applyTypeSpecificDefaults(v, type)
        if (changed || previousType == null) {
            val timeout = TIMEOUT_DEFAULTS[type] ?: DEFAULT_TIMEOUT
            v["timeout"] = clampTimeout(type, timeout, v["interval"].asDouble())
        }
        return v
    }

    private fun applyTypeSpecificDefaults(
        v: MutableMap<String, Any?>,
        type: String,
    ) {
        val port = v["port"].asId()
        if (port == null || port in KNOWN_DEFAULT_PORTS) v["port"] = PORT_DEFAULTS[type]
        if (type == "ntp" && v["interval"].asId() == DEFAULT_INTERVAL) setInterval(v, NTP_INTERVAL)
        val blankDefaults =
            when (type) {
                "push" -> mapOf("pushToken" to newPushToken())
                "dns" -> mapOf("dns_resolve_server" to DEFAULT_DNS_SERVER)
                "snmp" -> mapOf("snmpVersion" to "2c")
                else -> emptyMap()
            } + mapOf("jsonPath" to "$", "jsonPathOperator" to "==")
        blankDefaults.forEach { (key, default) -> if (v[key].isBlankValue()) v[key] = default }
        CONNECTION_TEMPLATES[type]?.let { template ->
            val current = v["databaseConnectionString"] as? String
            if (current.isNullOrBlank() ||
                current in CONNECTION_TEMPLATES.values
            ) {
                v["databaseConnectionString"] = template
            }
        }
        if (type == "globalping") applySubtype(v, v["subtype"] as? String ?: "ping")
    }

    private fun applyTargetDefaults(
        v: MutableMap<String, Any?>,
        type: String,
    ) {
        val url = v["url"] as? String
        if (type == "websocket-upgrade") {
            if (url.isNullOrBlank() || !url.startsWith("ws")) v["url"] = "wss://"
            if (v["accepted_statuscodes"] == listOf("200-299")) v["accepted_statuscodes"] = listOf("1000")
        } else {
            if (url.isNullOrBlank() || url == "wss://" || url == "ws://") v["url"] = "https://"
            if (v["accepted_statuscodes"] == listOf("1000") || v["accepted_statuscodes"].isBlankValue()) {
                v["accepted_statuscodes"] = listOf("200-299")
            }
        }
    }

    /** Globalping sub-form defaults (`protocol`, `port`) for [subtype]. */
    fun applySubtype(
        v: MutableMap<String, Any?>,
        subtype: String,
    ) {
        val previous = v["subtype"] as? String
        v["subtype"] = subtype
        val protocols = mapOf("ping" to "ICMP", "dns" to "UDP")
        if (previous != subtype || v["protocol"] == null) v["protocol"] = protocols[subtype]
        val port = v["port"].asId()
        if (port == null || port in KNOWN_DEFAULT_PORTS) v["port"] = if (subtype == "dns") DNS_PORT else HTTP_PORT
    }

    /**
     * Applies a single field edit plus the web UI's linked updates: the retry interval follows the
     * interval while both are equal, the timeout stays within 80 % of the interval (except ping),
     * `ignoreTls` disables the TLS expiry notification, and type/subtype changes apply their defaults.
     */
    fun change(
        spec: MonitorSpec,
        values: Map<String, Any?>,
        key: String,
        value: Any?,
    ): Map<String, Any?> {
        if (key == "type") return applyType(spec, values, value as? String ?: MonitorDraft.DEFAULT_TYPE)
        val v = values.toMutableMap()
        when (key) {
            "interval" -> {
                setInterval(v, value)
            }

            "subtype" -> {
                applySubtype(v, value as? String ?: "ping")
            }

            "ignoreTls" -> {
                v[key] = value
                if (value.asBoolean()) v["expiryNotification"] = false
            }

            else -> {
                v[key] = value
            }
        }
        return v
    }

    private fun setInterval(
        v: MutableMap<String, Any?>,
        value: Any?,
    ) {
        val old = v["interval"]
        v["interval"] = value
        if (old.asDouble() != null && v["retryInterval"].asDouble() == old.asDouble()) v["retryInterval"] = value
        val timeout = v["timeout"].asDouble() ?: return
        v["timeout"] = clampTimeout(v["type"] as? String, timeout, value.asDouble())
    }

    private fun clampTimeout(
        type: String?,
        timeout: Double,
        interval: Double?,
    ): Any {
        val tooLong = interval != null && interval > 0 && interval * TIMEOUT_FACTOR < timeout
        val limited = if (type != "ping" && tooLong) floor(interval * TIMEOUT_DECI) / TIMEOUT_DECI_DIV else timeout
        return if (limited % 1.0 == 0.0) limited.toLong() else limited
    }

    private const val TIMEOUT_DECI = 8.0
    private const val TIMEOUT_DECI_DIV = 10.0
    private const val PING_DEFAULT_TIMEOUT = 10L
    private const val DNS_PORT = 53L
    private const val HTTP_PORT = 80L

    /**
     * Turns `getMonitor`'s `MonitorJSON` (as plain Kotlin values) into a draft: tags are split off and
     * the web UI's load-time fixes are applied (missing timeout / retry interval, globalping check mode).
     */
    fun fromMonitorJson(json: Map<String, Any?>): MonitorDraft {
        val v = json.toMutableMap()
        val tags = (v.remove("tags") as? List<*>).orEmpty().mapNotNull { tagOf(it) }
        val interval = v["interval"].asDouble() ?: DEFAULT_INTERVAL.toDouble()
        val timeout = v["timeout"].asDouble()
        if (timeout == null || timeout <= 0.0) {
            v["timeout"] =
                if (v["type"] == "ping") PING_DEFAULT_TIMEOUT else clampTimeout(null, interval, interval)
        }
        if ((v["retryInterval"].asDouble() ?: 0.0) <= 0.0) v["retryInterval"] = v["interval"]
        if (v["accepted_statuscodes"].isBlankValue()) v["accepted_statuscodes"] = listOf("200-299")
        if (v["conditions"] == null) v["conditions"] = emptyList<Any?>()
        if (v["kafkaProducerBrokers"] == null) v["kafkaProducerBrokers"] = emptyList<Any?>()
        if (v["rabbitmqNodes"] == null) v["rabbitmqNodes"] = emptyList<Any?>()
        if (v["kafkaProducerSaslOptions"] == null) v["kafkaProducerSaslOptions"] = mapOf("mechanism" to "None")
        if (v["type"] == "globalping" && v["responsecheck"] == null) {
            v["responsecheck"] =
                when {
                    !v["keyword"].isBlankValue() -> "keyword"
                    !v["expectedValue"].isBlankValue() -> "json-query"
                    else -> null
                }
        }
        return MonitorDraft(id = v["id"].asId(), fields = v, tags = tags)
    }

    /** An unsaved copy of [source] (web UI "Clone"): no id/read-only fields, new push token, same tags. */
    fun clone(
        spec: MonitorSpec,
        source: MonitorDraft,
        name: String,
    ): MonitorDraft {
        val v = source.fields.toMutableMap()
        (spec.readonlyKeys + setOf("id", "active", "includeSensitiveData")).forEach { v.remove(it) }
        v["name"] = name
        v["pushToken"] = if (v["type"] == "push") newPushToken() else null
        return MonitorDraft(id = null, fields = v, tags = source.tags)
    }

    /** The name the web UI uses when the name field is left empty, `null` when nothing fits. */
    fun defaultName(values: Map<String, Any?>): String? {
        val url =
            (values["url"] as? String)?.trim()?.takeUnless {
                it in
                    setOf(
                        "http://",
                        "https://",
                        "ws://",
                        "wss://",
                    )
            }
        val urlHost = url?.let { runCatching { URI(it).host }.getOrNull() }
        return listOf(values["hostname"] as? String, values["system_service_name"] as? String, urlHost)
            .map { it?.trim() }
            .firstOrNull { !it.isNullOrEmpty() }
    }

    private fun tagOf(raw: Any?): MonitorTag? {
        val m = raw as? Map<*, *> ?: return null
        val id = (m["tag_id"] ?: m["id"]).asId() ?: return null
        return MonitorTag(
            tagId = id,
            name = m["name"]?.toString().orEmpty(),
            color = m["color"]?.toString().orEmpty(),
            value = m["value"]?.toString().orEmpty(),
        )
    }
}
