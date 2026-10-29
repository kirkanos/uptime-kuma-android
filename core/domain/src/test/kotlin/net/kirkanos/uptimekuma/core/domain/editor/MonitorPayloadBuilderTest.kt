package net.kirkanos.uptimekuma.core.domain.editor

import net.kirkanos.uptimekuma.core.model.MonitorDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MonitorPayloadBuilderTest {
    private val spec = MonitorSpec.bundled
    private val builder = MonitorPayloadBuilder(spec)

    @ParameterizedTest
    @MethodSource("allTypes")
    fun `add payload of every type only carries columns the server accepts`(type: String) {
        val draft = MonitorDrafts.newDraft(spec, type, notificationIds = listOf(3L))
        val payload = builder.forAdd(draft.with("name", "[test] $type"))

        assertEquals(type, payload["type"])
        val codes = payload["accepted_statuscodes"] as List<*>
        assertTrue(codes.isNotEmpty() && codes.all { it is String }, "accepted_statuscodes must be strings")
        assertEquals(mapOf("3" to true), payload["notificationIDList"])
        assertTrue(payload["conditions"] is List<*>)
        for (key in payload.keys) {
            assertFalse(key in spec.readonlyKeys, "$type: read-only $key sent")
            val field = spec.field(key)
            assertTrue(
                key in spec.defaultsNew || (field != null && field.dbColumn != null && !field.frontendOnly),
                "$type: $key is not a DB column",
            )
        }
        assertFalse("active" in payload)
        assertFalse("id" in payload)
        assertFalse("responsecheck" in payload)
        if (type !in MonitorFieldRules.HTTP_TYPES) assertNull(payload["httpBodyEncoding"])
        // Fields of the type are present (with their default or null).
        spec
            .type(type)!!
            .fields
            .filter { spec.field(it)?.dbColumn != null && it !in setOf("active", "tags") }
            .forEach { assertTrue(it in payload, "$type: $it missing") }
    }

    @Test
    fun `add payload matches the recorded web UI request`() {
        val recorded = Fixtures.request("add.ok")
        val draft =
            MonitorDrafts.newDraft(spec, "http").let { d ->
                recorded.entries.fold(d) { acc, (k, v) -> acc.with(k, v) }
            }
        val payload = builder.forAdd(draft)
        for ((key, value) in recorded) {
            if (key == "httpBodyEncoding") continue
            assertEquals(value, payload[key], key)
        }
        assertTrue(builder.addKeys("http").containsAll(recorded.keys))
        assertTrue(builder.addKeys("pm2").containsAll(Fixtures.request("add.validation-error.pm2-name").keys))
    }

    @Test
    fun `numbers typed as text, ids and status codes are normalised`() {
        val draft =
            MonitorDrafts
                .newDraft(spec, "port")
                .with("name", "n")
                .with("hostname", "example.com")
                .with("port", "8080")
                .with("interval", "30")
                .with("timeout", "12.5")
                .with("parent", "11")
                .with("accepted_statuscodes", listOf(200, "300-399"))
                .with("notificationIDList", mapOf("1" to true, "2" to false))
        val payload = builder.forAdd(draft)
        assertEquals(8080L, payload["port"])
        assertEquals(30L, payload["interval"])
        assertEquals(12.5, payload["timeout"])
        assertEquals(11L, payload["parent"])
        assertEquals(listOf("200", "300-399"), payload["accepted_statuscodes"])
        assertEquals(mapOf("1" to true), payload["notificationIDList"])
    }

    @Test
    fun `missing status codes get the type default`() {
        val ws = MonitorDrafts.newDraft(spec, "websocket-upgrade").with("accepted_statuscodes", null)
        assertEquals(listOf("1000"), builder.forAdd(ws)["accepted_statuscodes"])
        val http = MonitorDrafts.newDraft(spec, "http").with("accepted_statuscodes", emptyList<String>())
        assertEquals(listOf("200-299"), builder.forAdd(http)["accepted_statuscodes"])
    }

    @Test
    fun `empty name gets the default friendly name`() {
        val draft = MonitorDrafts.newDraft(spec, "http").with("url", "https://kuma.example.org/x")
        assertEquals("kuma.example.org", builder.forAdd(draft)["name"])
    }

    @Test
    fun `kafka and rabbitmq arrays are sent as JSON values`() {
        val kafka =
            MonitorDrafts
                .newDraft(spec, "kafka-producer")
                .with("kafkaProducerBrokers", "broker1:9092\nbroker2:9092")
                .with("kafkaProducerSaslOptions", mapOf("mechanism" to "plain", "username" to "u", "password" to "p"))
        val payload = builder.forAdd(kafka)
        assertEquals(listOf("broker1:9092", "broker2:9092"), payload["kafkaProducerBrokers"])
        assertEquals("plain", (payload["kafkaProducerSaslOptions"] as Map<*, *>)["mechanism"])
        val rabbit = MonitorDrafts.newDraft(spec, "rabbitmq").with("rabbitmqNodes", listOf("https://n1:15672", " "))
        assertEquals(listOf("https://n1:15672"), builder.forAdd(rabbit)["rabbitmqNodes"])
    }

    @Test
    fun `paused creation sends active false`() {
        val draft = MonitorDrafts.newDraft(spec, "group").with("active", false)
        assertEquals(false, builder.forAdd(draft)["active"])
    }

    @Test
    fun `edit payload is the full loaded object plus id without tags`() {
        val loaded = MonitorDrafts.fromMonitorJson(Fixtures.monitor("tagged"))
        val payload = builder.forEdit(loaded.with("name", "renamed"))
        assertEquals(8L, payload["id"])
        assertEquals("renamed", payload["name"])
        assertFalse("tags" in payload)
        assertTrue(
            payload.keys.containsAll(
                spec.editCopiedKeys -
                    setOf(
                        "manual_status",
                        "sshPassword",
                        "sshUsername",
                        "sshPrivateKey",
                        "sshPassphrase",
                    ),
            ),
        )
        assertEquals(listOf("200-299"), payload["accepted_statuscodes"])

        val recorded = Fixtures.request("editMonitor.ok")
        assertTrue(payload.keys.containsAll(recorded.keys))
    }

    @Test
    fun `edit needs an id`() {
        assertThrows<IllegalArgumentException> { builder.forEdit(MonitorDraft(fields = mapOf("type" to "http"))) }
    }

    @Test
    fun `loaded monitor keeps its tags apart and clone resets identity`() {
        val tagged = MonitorDrafts.fromMonitorJson(Fixtures.monitor("tagged"))
        assertEquals(8L, tagged.id)
        assertEquals(listOf("env" to "dev", "team" to "android"), tagged.tags.map { it.name to it.value })
        assertFalse("tags" in tagged.fields)

        val push = MonitorDrafts.fromMonitorJson(Fixtures.monitor("push"))
        val clone = MonitorDrafts.clone(spec, push, "Copy of ${push.name}")
        assertNull(clone.id)
        assertEquals("Copy of Push demo", clone.name)
        val token = clone["pushToken"] as String
        assertTrue(token.matches(Regex("[A-Za-z0-9]{32}")))
        assertNotEquals(push["pushToken"], token)
        val payload = builder.forAdd(clone)
        spec.readonlyKeys.forEach { assertFalse(it in payload, it) }
        assertEquals("push", payload["type"])
    }

    @Test
    fun `missing timeout and retry interval are filled like the web UI`() {
        val json = Fixtures.monitor("http") + mapOf("timeout" to 0L, "retryInterval" to 0L, "interval" to 30L)
        val draft = MonitorDrafts.fromMonitorJson(json)
        assertEquals(24L, draft["timeout"])
        assertEquals(30L, draft["retryInterval"])
        val ping = MonitorDrafts.fromMonitorJson(json + ("type" to "ping"))
        assertEquals(10L, ping["timeout"])
    }

    companion object {
        @JvmStatic
        fun allTypes(): List<String> = MonitorSpec.bundled.types.map { it.id }
    }
}
