package net.kirkanos.uptimekuma.core.domain.notification

import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.model.KumaNotification

/**
 * Editable state of one notification. [values] holds **every** provider key of the stored config, also
 * keys the spec does not know (legacy or newer fields); they are sent back unchanged.
 * [toggles] are UI-only switches referenced by visibility conditions (e.g. `showAdditionalHeadersField`).
 */
data class NotificationDraft(
    val id: Long?,
    val name: String,
    val type: String,
    val isDefault: Boolean = false,
    /** One-shot "apply on all existing monitors"; only offered when creating. */
    val applyExisting: Boolean = false,
    val active: Boolean = true,
    val values: Map<String, Any?> = emptyMap(),
    val toggles: Map<String, Boolean> = emptyMap(),
) {
    val isNew: Boolean get() = id == null
}

/** A validation problem of one field ([field] = `name` for the friendly name). */
data class NotificationIssue(
    val field: String,
    val kind: Kind,
    val min: Double? = null,
    val max: Double? = null,
) {
    enum class Kind { REQUIRED, NUMBER, RANGE, URL, EMAIL, JSON, PATTERN }
}

/** Form logic of the notification editor: drafts, visibility, validation and the payload. */
object NotificationForm {
    const val NAME = "name"
    private const val UNIFIED_PUSH_SUFFIX = " (UnifiedPush)"
    private const val UNIFIED_PUSH_MARKER = "?up=1"
    private val urlRegex = Regex("https?://.+", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("[^@\\s]+@[^@\\s]+")

    /**
     * Kuma's default name `My <Label> Alert (N)` with the smallest N not yet taken
     * (`NotificationDialog.vue:511-521`).
     */
    fun defaultName(
        provider: ProviderSpec,
        existingNames: Collection<String>,
    ): String {
        val taken = existingNames.toSet()
        var n = 1
        while ("My ${provider.shortLabel} Alert ($n)" in taken) n++
        return "My ${provider.shortLabel} Alert ($n)"
    }

    /** A new notification of [provider] with the spec defaults. */
    fun newDraft(
        provider: ProviderSpec,
        existingNames: Collection<String> = emptyList(),
    ): NotificationDraft {
        val defaults = provider.fields.filter { it.hasDefault }.associate { it.name to it.default }
        return NotificationDraft(
            id = null,
            name = defaultName(provider, existingNames),
            type = provider.id,
            values = defaults,
            toggles = initialToggles(provider, defaults),
        )
    }

    /** Draft of an existing notification; unknown keys are kept, nothing is defaulted. */
    fun fromNotification(
        notification: KumaNotification,
        provider: ProviderSpec?,
    ): NotificationDraft =
        NotificationDraft(
            id = notification.id,
            name = notification.name,
            type = notification.type,
            isDefault = notification.isDefault,
            applyExisting = false,
            active = notification.active,
            values = notification.fields,
            toggles = provider?.let { initialToggles(it, notification.fields) }.orEmpty(),
        )

    /** A UI toggle starts on when one of the fields it reveals has a value (web UI: `!= null`). */
    private fun initialToggles(
        provider: ProviderSpec,
        values: Map<String, Any?>,
    ): Map<String, Boolean> =
        provider.uiToggles.associateWith { toggle ->
            provider.fields
                .filter { toggle in it.visibility.identifiers() }
                .any { !isBlank(values[it.name]) }
        }

    fun isVisible(
        field: ProviderFieldSpec,
        draft: NotificationDraft,
    ): Boolean = field.visibility.isVisible(draft.values, draft.toggles)

    fun visibleFields(
        provider: ProviderSpec,
        draft: NotificationDraft,
    ): List<ProviderFieldSpec> = provider.fields.filter { isVisible(it, draft) }

    /** Spec fields hidden behind the UI toggle [toggle]. */
    fun fieldsOfToggle(
        provider: ProviderSpec,
        toggle: String,
    ): List<ProviderFieldSpec> = provider.fields.filter { toggle in it.visibility.identifiers() }

    /** Validation of the visible fields plus the friendly name. Empty = valid. */
    fun validate(
        provider: ProviderSpec?,
        draft: NotificationDraft,
    ): List<NotificationIssue> =
        buildList {
            // Like the web UI, names need not be unique.
            if (draft.name.isBlank()) add(NotificationIssue(NAME, NotificationIssue.Kind.REQUIRED))
            provider?.let { p ->
                visibleFields(p, draft).forEach { field -> check(field, draft.values[field.name])?.let(::add) }
            }
        }

    private fun check(
        field: ProviderFieldSpec,
        value: Any?,
    ): NotificationIssue? {
        val blank = isBlank(value)
        if (blank) {
            return NotificationIssue(field.name, NotificationIssue.Kind.REQUIRED).takeIf {
                field.required && field.kind != ProviderFieldKind.CHECKBOX &&
                    field.kind != ProviderFieldKind.UNSUPPORTED
            }
        }
        val text = value.toString().trim()
        return when (field.kind) {
            ProviderFieldKind.NUMBER -> {
                checkNumber(field, value)
            }

            ProviderFieldKind.URL -> {
                issue(field, NotificationIssue.Kind.URL).takeUnless { urlRegex.matches(text) }
            }

            ProviderFieldKind.EMAIL -> {
                issue(
                    field,
                    NotificationIssue.Kind.EMAIL,
                ).takeUnless { emailRegex.matches(text) }
            }

            ProviderFieldKind.JSON_OBJECT -> {
                issue(
                    field,
                    NotificationIssue.Kind.JSON,
                ).takeUnless { isJsonObject(value) }
            }

            ProviderFieldKind.TEXT, ProviderFieldKind.PASSWORD -> {
                checkPattern(field, text)
            }

            else -> {
                null
            }
        }
    }

    private fun checkNumber(
        field: ProviderFieldSpec,
        value: Any?,
    ): NotificationIssue? {
        val number = toNumber(value)?.toDouble() ?: return issue(field, NotificationIssue.Kind.NUMBER)
        val outOfRange = (field.min != null && number < field.min) || (field.max != null && number > field.max)
        return if (outOfRange) {
            NotificationIssue(
                field.name,
                NotificationIssue.Kind.RANGE,
                field.min,
                field.max,
            )
        } else {
            null
        }
    }

    /** HTML `pattern` semantics (whole value); skipped for inputs that exist with several types. */
    private fun checkPattern(
        field: ProviderFieldSpec,
        text: String,
    ): NotificationIssue? {
        val pattern = field.pattern?.takeIf { field.altTypes.isEmpty() } ?: return null
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
        return issue(field, NotificationIssue.Kind.PATTERN).takeUnless { regex.matches(text) }
    }

    private fun issue(
        field: ProviderFieldSpec,
        kind: NotificationIssue.Kind,
    ) = NotificationIssue(field.name, kind)

    private fun isJsonObject(value: Any?): Boolean =
        when (value) {
            is Map<*, *> -> true
            is String -> JsonValues.toKotlin(JsonValues.parseOrNull(value)) is Map<*, *>
            else -> false
        }

    /**
     * The notification to send to `addNotification` / `testNotification`. Starts from all stored keys,
     * normalises the spec fields (numbers from text, trimmed lists) and clears non-blank fields whose UI
     * toggle is off (Kuma treats `null` as "not set", e.g. webhook additional headers).
     */
    fun toNotification(
        provider: ProviderSpec?,
        draft: NotificationDraft,
    ): KumaNotification {
        val values = LinkedHashMap(draft.values)
        provider?.fields?.forEach { field ->
            if (field.name in values) values[field.name] = normalize(field, values[field.name])
        }
        // A blank stored value already means "not set"; it is left as it was.
        provider
            ?.let { p ->
                draft.toggles
                    .filterValues { !it }
                    .keys
                    .flatMap { fieldsOfToggle(p, it) }
            }?.filterNot { isBlank(values[it.name]) }
            ?.forEach { values[it.name] = null }
        return KumaNotification(
            id = draft.id,
            name = draft.name.trim(),
            type = draft.type,
            isDefault = draft.isDefault,
            active = draft.active,
            fields = values,
        )
    }

    private fun normalize(
        field: ProviderFieldSpec,
        value: Any?,
    ): Any? =
        when (field.kind) {
            ProviderFieldKind.NUMBER -> {
                if (value is String) {
                    if (value.isBlank()) null else toNumber(value) ?: value
                } else {
                    value
                }
            }

            ProviderFieldKind.LIST -> {
                when (value) {
                    is String -> splitList(value)
                    is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                    else -> value
                }
            }

            else -> {
                value
            }
        }

    /** Text -> list for multiselect fields: one entry per line or comma. */
    fun splitList(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Parses a number typed into a field: integral values become [Long]. */
    fun toNumber(value: Any?): Number? =
        when (value) {
            is Number -> value
            is String -> value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
            else -> null
        }

    fun isBlank(value: Any?): Boolean =
        when (value) {
            null -> true
            is String -> value.isBlank()
            is Collection<*> -> value.isEmpty()
            is Map<*, *> -> value.isEmpty()
            else -> false
        }

    /**
     * The device's own push webhook created by the push setup: `<device> (UnifiedPush)` posting to a
     * UnifiedPush endpoint (`...?up=1`).
     */
    fun isThisDevicePush(notification: KumaNotification): Boolean =
        notification.name.endsWith(UNIFIED_PUSH_SUFFIX) &&
            (notification.fields["webhookURL"] as? String).orEmpty().contains(UNIFIED_PUSH_MARKER)

    /** Select option matching a stored value (case-insensitive string compare, `"5"` matches `5`). */
    fun matchOption(
        field: ProviderFieldSpec,
        value: Any?,
    ): FieldOption? {
        val key = canonical(value) ?: return null
        return field.options.firstOrNull { canonical(it.value) == key }
            ?: field.options.firstOrNull { canonical(it.value).equals(key, ignoreCase = true) }
    }

    private fun canonical(value: Any?): String? =
        when (value) {
            null -> null
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
}
