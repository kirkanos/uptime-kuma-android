package net.kirkanos.uptimekuma.core.data.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import net.kirkanos.uptimekuma.core.data.FakeConfigStore
import net.kirkanos.uptimekuma.core.data.FakeKumaCache
import net.kirkanos.uptimekuma.core.data.FakeSecretStore
import net.kirkanos.uptimekuma.core.data.connection.ConnectionTimings
import net.kirkanos.uptimekuma.core.data.connection.KumaConnectionManager
import net.kirkanos.uptimekuma.core.data.connection.SocketKumaApiFactory
import net.kirkanos.uptimekuma.core.domain.notification.NotificationDraft
import net.kirkanos.uptimekuma.core.domain.notification.NotificationForm
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.kumaclient.NotificationConfig
import net.kirkanos.uptimekuma.core.kumaclient.dto.NotificationDto
import net.kirkanos.uptimekuma.core.kumaclient.json.KumaJson
import net.kirkanos.uptimekuma.core.model.ConnectionState
import net.kirkanos.uptimekuma.core.model.KumaException
import net.kirkanos.uptimekuma.core.model.KumaNotification
import net.kirkanos.uptimekuma.core.model.LoginResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Notification management against the dev Kuma (`infra/docker-compose.dev.yml`): creates `[P3-NOTIF] …`
 * notifications of several providers with the editor's form logic, tests, edits and deletes them. Never
 * touches other notifications. Run: `./gradlew :core:data:testDebugUnitTest -Pintegration
 * --tests '*NotificationIntegrationTest*'`.
 */
@Tag("integration")
class NotificationIntegrationTest {
    private val url = System.getProperty("kuma.url") ?: "http://localhost:3001"
    private val user = System.getProperty("kuma.user") ?: "admin"
    private val password = System.getProperty("kuma.password") ?: "devpassword123!"
    private val spec = NotificationProviderSpec.bundled

    private fun draft(
        type: String,
        name: String,
        vararg values: Pair<String, Any?>,
    ): NotificationDraft {
        val provider = requireNotNull(spec.provider(type))
        val draft =
            NotificationForm.newDraft(provider).let {
                it.copy(
                    name = "$PREFIX $name",
                    values =
                        it.values + values,
                )
            }
        val issues = NotificationForm.validate(provider, draft)
        check(issues.isEmpty()) { "$name: $issues" }
        return draft
    }

    @Test
    @Suppress("LongMethod")
    fun `create, test, edit and delete notifications of several providers`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val m =
                KumaConnectionManager(
                    SocketKumaApiFactory(),
                    FakeKumaCache(),
                    FakeConfigStore(),
                    FakeSecretStore(),
                    emptySet(),
                    scope,
                    ConnectionTimings(),
                )
            val admin = KumaNotificationAdminRepository(ConnectionNotificationAdminBackend(m))
            val created = mutableListOf<Long>()

            fun decode(args: List<JsonElement>): List<KumaNotification> =
                (args.first() as JsonArray).map {
                    NotificationConfig.toModel(KumaJson.json.decodeFromJsonElement(NotificationDto.serializer(), it))
                }

            /** The pushed `notificationList` once it satisfies [predicate] (it arrives after each ack). */
            suspend fun list(predicate: (List<KumaNotification>) -> Boolean = { true }): List<KumaNotification> =
                withTimeout(TIMEOUT) {
                    m.snapshot("notificationList").first { it != null && predicate(decode(it)) }!!.let(::decode)
                }

            suspend fun save(draft: NotificationDraft): Long {
                val notification = NotificationForm.toNotification(spec.provider(draft.type), draft)
                // Exactly what RoomKumaNotificationRepository.save sends.
                return m.withApi {
                    it.addNotification(
                        NotificationConfig.toPayload(notification, draft.applyExisting),
                        draft.id,
                    )
                }
            }

            suspend fun test(draft: NotificationDraft): String =
                try {
                    "OK: " + admin.sendTest(NotificationForm.toNotification(spec.provider(draft.type), draft))
                } catch (e: KumaException) {
                    "FAILED (${e.error.kind}): ${e.error.message}"
                }

            try {
                m.setForeground(true)
                assertEquals(LoginResult.Success, m.login(url, user, password, null))
                withTimeout(TIMEOUT) { m.state.first { it is ConnectionState.Connected } }
                val before = list()
                println("existing notifications: ${before.map { "${it.id}:${it.name}" }}")
                println("apprise available: ${admin.isAppriseAvailable()}")

                // The dev ntfy denies anonymous publishing. Borrow the publish token of an existing push webhook
                // (read only) so the success path can be exercised too; without one the tests report 403.
                val token =
                    System.getProperty("kuma.ntfyToken")
                        ?: before
                            .firstNotNullOfOrNull { n ->
                                Regex(
                                    "Bearer (tk_\\w+)",
                                ).find(n.fields["webhookAdditionalHeaders"]?.toString().orEmpty())
                            }?.groupValues
                            ?.get(1)
                println("ntfy token available: ${token != null}")
                val auth = token?.let { """{"Authorization":"Bearer $it"}""" }
                val drafts =
                    listOf(
                        draft(
                            "webhook",
                            "webhook",
                            "webhookURL" to "http://kuma-dev-ntfy:80/upP3TEST?up=1",
                            "webhookAdditionalHeaders" to auth,
                        ).copy(toggles = mapOf("showAdditionalHeadersField" to (auth != null))),
                        draft(
                            "webhook",
                            "webhook unauthorized",
                            "webhookURL" to "http://kuma-dev-ntfy:80/upP3TEST?up=1",
                        ),
                        draft("webhook", "webhook error", "webhookURL" to "http://localhost:9"),
                        draft(
                            "ntfy",
                            "ntfy",
                            "ntfyserverurl" to "http://ntfy:80",
                            "ntfytopic" to "upP3",
                            "ntfyAuthenticationMethod" to if (token != null) "accessToken" else "none",
                            "ntfyaccesstoken" to token,
                            "ntfyPriority" to "4",
                        ),
                        draft(
                            "smtp",
                            "smtp",
                            "smtpHost" to "localhost",
                            "smtpPort" to "2525",
                            "smtpFrom" to "kuma@example.org",
                            "smtpTo" to "ops@example.org",
                        ),
                        draft(
                            "telegram",
                            "telegram",
                            "telegramBotToken" to "123456:dummy",
                            "telegramChatID" to "-100123",
                        ),
                        draft("discord", "discord", "discordWebhookUrl" to "https://discord.com/api/webhooks/0/dummy"),
                    )
                val outcomes = mutableListOf<String>()
                for (d in drafts) {
                    // Unsaved config first, like the editor's Test button.
                    outcomes += "${d.name} (unsaved): ${test(d)}"
                    val id = save(d)
                    created += id
                    val stored = list { l -> l.any { it.id == id } }.first { it.id == id }
                    assertEquals(d.name, stored.name)
                    assertEquals(d.type, stored.type)
                    val sent = NotificationForm.toNotification(spec.provider(d.type), d).fields
                    sent.forEach { (k, v) -> assertEquals(v, stored.fields[k], "${d.name}.$k") }
                }
                outcomes.forEach(::println)

                // Edit: ntfy priority and name; unknown/legacy keys of the stored config survive.
                val ntfyId = created[NTFY_INDEX]
                val stored = list().first { it.id == ntfyId }
                val provider = spec.provider("ntfy")
                val edited =
                    NotificationForm.fromNotification(stored, provider).let {
                        it.copy(name = "$PREFIX ntfy edited", values = it.values + ("ntfyPriority" to "2"))
                    }
                assertEquals(ntfyId, save(edited))
                val after =
                    list { l -> l.any { it.id == ntfyId && it.name.endsWith("edited") } }.first {
                        it.id ==
                            ntfyId
                    }
                assertEquals("$PREFIX ntfy edited", after.name)
                assertEquals(2L, after.fields["ntfyPriority"])
                assertEquals(stored.fields.keys, after.fields.keys)
                println("edited #$ntfyId -> ${after.name}, priority ${after.fields["ntfyPriority"]}")

                // The other notifications were not touched.
                val untouched = list().filterNot { it.id in created }
                assertEquals(before, untouched)
            } finally {
                // Delete everything this test created (also leftovers of earlier runs), nothing else.
                runCatching {
                    val leftovers = list().filter { it.name.startsWith(PREFIX) }.mapNotNull { it.id }
                    (created + leftovers).distinct().forEach { id ->
                        runCatching { m.withApi { it.deleteNotification(id) } }
                    }
                    val remaining = list { l -> l.none { it.name.startsWith(PREFIX) } }
                    println(
                        "deleted ${(created + leftovers).distinct().size}; ${remaining.size} other notifications left",
                    )
                }.onFailure { println("cleanup failed: $it") }
                m.logout()
                scope.cancel()
            }
        }

    private companion object {
        const val PREFIX = "[P3-NOTIF]"
        const val NTFY_INDEX = 3
        const val TIMEOUT = 20_000L
    }
}
