package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.kumaclient.json.asLenientBoolean
import net.kirkanos.uptimekuma.core.kumaclient.json.asStringOrNull
import net.kirkanos.uptimekuma.core.kumaclient.json.toJsonElement
import net.kirkanos.uptimekuma.core.kumaclient.json.toPlainValue
import net.kirkanos.uptimekuma.core.model.KumaNotification

/**
 * Conversion between Kuma's notification representation (a flat JSON object stored as string in
 * `notification.config`) and [KumaNotification] (meta fields + provider [KumaNotification.fields]).
 */
object NotificationConfig {
    /** Keys that are not provider settings. They live on [KumaNotification] itself. */
    val META_KEYS: Set<String> =
        setOf("id", "name", "type", "isDefault", "is_default", "applyExisting", "active", "userId", "user_id")

    /** Parses the `config` JSON string; `null` when missing or malformed. */
    fun parse(config: String?): JsonObject? {
        if (config.isNullOrBlank()) return null
        return try {
            KumaJson.json.parseToJsonElement(config) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Provider fields of a parsed config as plain Kotlin values. */
    fun fieldsOf(config: JsonObject?): Map<String, Any?> =
        config
            ?.filterKeys { it !in META_KEYS }
            ?.mapValues { (_, value) -> value.toPlainValue() }
            .orEmpty()

    fun toModel(dto: NotificationDto): KumaNotification {
        val config = parse(dto.config)
        return KumaNotification(
            id = dto.id,
            name = dto.name ?: config?.get("name").asStringOrNull().orEmpty(),
            type = config?.get("type").asStringOrNull().orEmpty(),
            isDefault = dto.isDefault || dto.isDefaultRaw || config?.get("isDefault").asLenientBoolean() == true,
            active = dto.active,
            fields = fieldsOf(config),
        )
    }

    /**
     * Builds the object passed to `addNotification` / `testNotification`, mirroring the web UI's
     * `NotificationDialog`: provider fields plus `name`, `type`, `isDefault`, `active`, `applyExisting`
     * in one flat object (meta keys win over equally named provider fields).
     */
    fun toPayload(
        notification: KumaNotification,
        applyExisting: Boolean = false,
    ): JsonObject {
        val content = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        notification.fields
            .filterKeys { it !in META_KEYS }
            .forEach { (key, value) -> content[key] = value.toJsonElement() }
        content["name"] = JsonPrimitive(notification.name)
        content["type"] = JsonPrimitive(notification.type)
        content["isDefault"] = JsonPrimitive(notification.isDefault)
        content["active"] = JsonPrimitive(notification.active)
        content["applyExisting"] = JsonPrimitive(applyExisting)
        return JsonObject(content)
    }
}
