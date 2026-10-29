package net.kirkanos.uptimekuma.core.data.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.domain.editor.asBoolean
import net.kirkanos.uptimekuma.core.domain.editor.asId
import net.kirkanos.uptimekuma.core.model.PickerOption
import net.kirkanos.uptimekuma.core.model.Tag

/** Decoders for the snapshot list events and tag acks the editor uses (lenient: ids/booleans as numbers or strings). */
internal object PickerDecoders {
    /** `proxyList`: `[{id, protocol, host, port, default, ...}]` -> "protocol://host:port". */
    fun proxies(args: List<JsonElement>?): List<PickerOption> =
        rows(args).mapNotNull { row ->
            val id = row["id"].asId() ?: return@mapNotNull null
            val label = "${row["protocol"] ?: "http"}://${row["host"].text()}:${row["port"].text()}"
            PickerOption(id = id, label = label, isDefault = row["default"].asBoolean())
        }

    /** `dockerHostList` / `remoteBrowserList`: `[{id, name, ...}]`. */
    fun named(args: List<JsonElement>?): List<PickerOption> =
        rows(args).mapNotNull { row ->
            val id = row["id"].asId() ?: return@mapNotNull null
            PickerOption(id = id, label = row["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "#$id")
        }

    fun tags(element: JsonElement?): List<Tag> = (element as? JsonArray).orEmpty().mapNotNull { tag(it) }

    fun tag(element: JsonElement?): Tag? {
        val row = JsonValues.toKotlin(element as? JsonObject) as? Map<*, *> ?: return null
        val id = row["id"].asId() ?: return null
        return Tag(id = id, name = row["name"]?.toString().orEmpty(), color = row["color"]?.toString().orEmpty())
    }

    private fun rows(args: List<JsonElement>?): List<Map<*, *>> =
        (JsonValues.toKotlin(args?.firstOrNull()) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun Any?.text(): String = this?.toString().orEmpty()
}
