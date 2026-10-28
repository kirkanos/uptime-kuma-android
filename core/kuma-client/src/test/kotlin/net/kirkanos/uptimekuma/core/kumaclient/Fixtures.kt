package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import java.io.File

/** Access to the recorded Kuma 2.5.8 payloads in `fixtures/kuma-2.5.8`. */
object Fixtures {
    val dir: File by lazy {
        val configured = System.getProperty("kuma.fixtures")?.let(::File)
        val candidates = listOfNotNull(configured, File("../../fixtures/kuma-2.5.8"), File("fixtures/kuma-2.5.8"))
        candidates.firstOrNull { it.isDirectory } ?: error("Fixture directory not found, tried $candidates")
    }

    fun json(name: String): JsonObject = KumaJson.json.parseToJsonElement(File(dir, name).readText()).jsonObject

    /** Positional args of every recorded sample of a server -> client event fixture. */
    fun eventSamples(event: String): List<List<JsonElement>> =
        json("$event.json").getValue("samples").jsonArray.map { sample ->
            (sample.jsonObject["args"] as JsonArray).toList()
        }

    /** Parsed events of every sample. */
    fun events(event: String): List<KumaEvent> =
        eventSamples(event).map { args -> requireNotNull(KumaEventParser.parse(event, args)) { "$event: $args" } }

    /** The `response` (first ack argument) of an `ack/<name>.json` fixture. */
    fun ackResponse(name: String): JsonElement? = json("ack/$name.json")["response"]

    fun ackRequest(name: String): JsonArray = json("ack/$name.json").getValue("request").jsonArray
}
