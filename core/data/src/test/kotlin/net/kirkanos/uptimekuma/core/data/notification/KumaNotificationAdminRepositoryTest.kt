package net.kirkanos.uptimekuma.core.data.notification

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class KumaNotificationAdminRepositoryTest {
    private class FakeBackend : NotificationAdminBackend {
        val payloads = mutableListOf<JsonObject>()
        var message: String? = "Sent Successfully."
        var error: KumaException? = null
        var apprise: List<JsonElement> = listOf(JsonPrimitive(true))

        override suspend fun testNotification(payload: JsonObject): String? {
            payloads += payload
            error?.let { throw it }
            return message
        }

        override suspend fun checkApprise(): List<JsonElement> = apprise
    }

    private val backend = FakeBackend()
    private val repo = KumaNotificationAdminRepository(backend)
    private val spec = NotificationProviderSpec.bundled

    private fun fixture(name: String): JsonObject {
        val dir =
            System.getProperty("kuma.fixtures")?.let(::File)
                ?: File(System.getProperty("user.dir")).resolve("../../fixtures/kuma-2.5.8").normalize()
        return KumaJson.json.parseToJsonElement(File(dir, name).readText()).jsonObject
    }

    @Test
    fun `test sends the flat NotificationDialog object and returns Kuma's message`() =
        runTest {
            val provider = spec.provider("ntfy")!!
            val draft =
                NotificationForm.newDraft(provider).let {
                    it.copy(
                        name = "[P3-NOTIF] ntfy",
                        values =
                            it.values + mapOf("ntfytopic" to "upP3", "ntfyPriority" to "4"),
                    )
                }
            val message = repo.sendTest(NotificationForm.toNotification(provider, draft))
            assertEquals("Sent Successfully.", message)
            val payload = backend.payloads.single()
            assertEquals("[P3-NOTIF] ntfy", payload["name"]!!.jsonPrimitive.content)
            assertEquals("ntfy", payload["type"]!!.jsonPrimitive.content)
            assertEquals("upP3", payload["ntfytopic"]!!.jsonPrimitive.content)
            // Typed like the web UI's number input, not the text the user typed.
            assertEquals(JsonPrimitive(4L), payload["ntfyPriority"])
            assertEquals(JsonPrimitive(false), payload["isDefault"])
            assertEquals(JsonPrimitive(false), payload["applyExisting"])
            assertFalse("id" in payload)
        }

    @Test
    fun `failed test surfaces the server message`() =
        runTest {
            backend.error = KumaException(KumaError(KumaError.Kind.SERVER_REJECTED, "connect ECONNREFUSED 127.0.0.1:9"))
            val provider = spec.provider("webhook")!!
            val e =
                assertThrows<KumaException> {
                    repo.sendTest(NotificationForm.toNotification(provider, NotificationForm.newDraft(provider)))
                }
            assertEquals("connect ECONNREFUSED 127.0.0.1:9", e.error.message)
        }

    @Test
    fun `recorded configs are sent back with every stored key`() =
        runTest {
            val rows =
                fixture("notificationList.json")["samples"]!!
                    .let { (it as JsonArray).first().jsonObject["args"] as JsonArray }
                    .first() as JsonArray
            for (row in rows) {
                val dto = KumaJson.json.decodeFromJsonElement(NotificationDto.serializer(), row)
                val notification = NotificationConfig.toModel(dto)
                val provider = spec.provider(notification.type)
                val sent =
                    NotificationForm.toNotification(
                        provider,
                        NotificationForm.fromNotification(notification, provider),
                    )
                repo.sendTest(sent)
                val payload = backend.payloads.last()
                val config = NotificationConfig.parse(dto.config)!!
                // Everything the server stored comes back unchanged (applyExisting is always false on edit).
                assertEquals(config, JsonObject(payload.filterKeys { it in config.keys }), notification.name)
                assertEquals(config.keys + "active", payload.keys)
            }
        }

    @Test
    fun `checkApprise reads the bare boolean ack`() =
        runTest {
            assertTrue(repo.isAppriseAvailable())
            backend.apprise = listOf(JsonPrimitive(false))
            assertFalse(repo.isAppriseAvailable())
            backend.apprise = emptyList()
            assertFalse(repo.isAppriseAvailable())
            val ack = fixture("ack/checkApprise.json")["response"]!!
            backend.apprise = listOf(ack)
            assertTrue(repo.isAppriseAvailable())
        }
}
