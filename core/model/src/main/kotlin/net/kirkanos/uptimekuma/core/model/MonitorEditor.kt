package net.kirkanos.uptimekuma.core.model

/**
 * An editable monitor: Kuma's monitor object as a generic field map, keyed by the property names of
 * `spec/monitor-types.json` (e.g. `url`, `interval`, `accepted_statuscodes`).
 *
 * Values are JSON-compatible Kotlin values: `String`, `Long`/`Int`/`Double`, `Boolean`, `null`,
 * `List<Any?>` and `Map<String, Any?>`. While editing, number fields may temporarily hold the raw
 * text the user typed; the payload builder normalises them. Tags are kept apart because Kuma manages
 * them with separate events after the monitor was saved.
 */
data class MonitorDraft(
    /** `null` for a monitor that does not exist yet (new or clone). */
    val id: Long? = null,
    val fields: Map<String, Any?>,
    /** Desired tags; diffed against the server state on save. */
    val tags: List<MonitorTag> = emptyList(),
) {
    val type: String get() = fields["type"] as? String ?: DEFAULT_TYPE

    val name: String get() = fields["name"] as? String ?: ""

    val isNew: Boolean get() = id == null

    operator fun get(key: String): Any? = fields[key]

    fun with(
        key: String,
        value: Any?,
    ): MonitorDraft = copy(fields = fields + (key to value))

    companion object {
        const val DEFAULT_TYPE = "http"
    }
}

/** A selectable entity in the editor (notification, docker host, remote browser, group, ...). */
data class PickerOption(
    val id: Long,
    val label: String,
    /** Pre-selected for new monitors (default notification / default proxy). */
    val isDefault: Boolean = false,
)

/** Everything the editor offers in its selection fields. */
data class EditorPickers(
    val notifications: List<PickerOption> = emptyList(),
    val proxies: List<PickerOption> = emptyList(),
    val dockerHosts: List<PickerOption> = emptyList(),
    val remoteBrowsers: List<PickerOption> = emptyList(),
    /** Group monitors usable as parent. */
    val groups: List<PickerOption> = emptyList(),
    val tags: List<Tag> = emptyList(),
)
