package net.kirkanos.uptimekuma.core.domain.editor

import net.kirkanos.uptimekuma.core.model.MonitorDraft

/**
 * Builds the monitor object for Kuma's `add` and `editMonitor` events from a [MonitorDraft]
 * (rules: `docs/kuma-api/monitor-types.md` sections 4/5, pitfalls 12/13).
 *
 * - `add` imports every property as a DB column and fails on unknown ones, so it only gets the keys the
 *   web UI sends for the type: `monitor_defaults_new` plus the type's own fields, restricted to fields
 *   with a DB column (no read-only `MonitorJSON` keys, no frontend helpers, no `tags`, no `id`).
 * - `editMonitor` copies an explicit field list and ignores the rest, so it gets the full object plus `id`.
 * - Both: `accepted_statuscodes` is always a string array, `notificationIDList` is `{"<id>": true}`,
 *   `conditions`/`kafkaProducerBrokers`/`rabbitmqNodes`/`kafkaProducerSaslOptions` are JSON values,
 *   numbers typed as text are converted, an empty name gets the web UI's default name.
 */
class MonitorPayloadBuilder(
    private val spec: MonitorSpec,
) {
    private val alwaysSent = setOf("type", "name", "accepted_statuscodes", "notificationIDList", "conditions")
    private val neverOnAdd = setOf("id", "tags", "active", "includeSensitiveData")

    /** Keys of [type] that `add` accepts. */
    fun addKeys(type: String): Set<String> {
        val typeFields = spec.type(type)?.fields.orEmpty()
        val candidates = spec.defaultsNew.keys + typeFields + alwaysSent
        return candidates
            .filter { key ->
                val field = spec.field(key)
                key in alwaysSent || key in spec.defaultsNew ||
                    (field != null && field.dbColumn != null && !field.frontendOnly)
            }.filterNot { it in spec.readonlyKeys || it in neverOnAdd }
            .toCollection(LinkedHashSet())
    }

    fun forAdd(draft: MonitorDraft): Map<String, Any?> {
        val type = draft.type
        val out = linkedMapOf<String, Any?>()
        for (key in addKeys(type)) {
            out[key] = normalize(key, if (key in draft.fields) draft.fields[key] else spec.defaultsNew[key])
        }
        finish(out, draft)
        // `active: false` is a real column honoured by add (creates the monitor paused).
        if (draft.fields["active"] == false) out["active"] = false
        return out
    }

    fun forEdit(draft: MonitorDraft): Map<String, Any?> {
        val id = requireNotNull(draft.id) { "editMonitor needs a monitor id" }
        val out = linkedMapOf<String, Any?>()
        for ((key, value) in draft.fields) {
            if (key == "tags") continue
            out[key] = normalize(key, value)
        }
        for (key in alwaysSent) if (key !in out) out[key] = normalize(key, spec.defaultsNew[key])
        finish(out, draft)
        out["id"] = id
        return out
    }

    private fun finish(
        out: MutableMap<String, Any?>,
        draft: MonitorDraft,
    ) {
        out["type"] = draft.type
        if (out["name"].isBlankValue()) MonitorDrafts.defaultName(draft.fields)?.let { out["name"] = it }
        if (draft.type !in MonitorFieldRules.HTTP_TYPES && "httpBodyEncoding" in out) out["httpBodyEncoding"] = null
        if (out["accepted_statuscodes"].isBlankValue()) {
            out["accepted_statuscodes"] = listOf(if (draft.type == "websocket-upgrade") "1000" else "200-299")
        }
    }

    /** Converts an editor value of [key] to the JSON shape Kuma expects. */
    fun normalize(
        key: String,
        value: Any?,
    ): Any? {
        val field = spec.field(key)
        return when {
            key == "accepted_statuscodes" -> stringList(value)
            key in JSON_LIST_KEYS -> if (key == "conditions") (value as? List<*>).orEmpty() else stringList(value)
            key == "kafkaProducerSaslOptions" -> (value as? Map<*, *>) ?: mapOf("mechanism" to "None")
            key == "notificationIDList" -> notificationMap(value)
            key == "expectedValue" -> value?.let { if (it is Number) numberText(it) else it.toString() }
            key in ID_KEYS -> value.asId()
            field == null -> value
            field.kind == FieldKind.NUMBER || key == "manual_status" -> number(value)
            field.kind == FieldKind.CHECKBOX -> if (value == null) null else value.asBoolean()
            else -> value
        }
    }

    companion object {
        private val JSON_LIST_KEYS = setOf("conditions", "kafkaProducerBrokers", "rabbitmqNodes")
        private val ID_KEYS = setOf("proxyId", "docker_host", "remote_browser", "parent")

        /** List of strings from a list or newline/comma separated text; blanks dropped. */
        fun stringList(value: Any?): List<String> =
            when (value) {
                null -> emptyList()
                is Iterable<*> -> value.mapNotNull { it?.let { v -> if (v is Number) numberText(v) else v.toString() } }
                is String -> value.split('\n', ',')
                else -> listOf(value.toString())
            }.map { it.trim() }.filter { it.isNotEmpty() }

        /** `{"<id>": true}` from a map (truthy entries only) or a collection of ids. */
        fun notificationMap(value: Any?): Map<String, Boolean> =
            when (value) {
                is Map<*, *> -> value.filterValues { it.asBoolean() }.keys.associate { it.toString() to true }
                is Iterable<*> -> value.mapNotNull { it.asId() }.associate { it.toString() to true }
                else -> emptyMap()
            }

        private fun number(value: Any?): Number? {
            val d = value.asDouble() ?: return null
            return if (d % 1.0 == 0.0) d.toLong() else d
        }

        private fun numberText(n: Number): String {
            val d = n.toDouble()
            return if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        }
    }
}
