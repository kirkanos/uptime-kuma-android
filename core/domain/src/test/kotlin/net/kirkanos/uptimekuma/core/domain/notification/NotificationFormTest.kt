package net.kirkanos.uptimekuma.core.domain.notification

import net.kirkanos.uptimekuma.core.domain.editor.JsonValues
import net.kirkanos.uptimekuma.core.model.KumaNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NotificationFormTest {
    private val spec = NotificationProviderSpec.bundled
    private val webhook = spec.provider("webhook")!!
    private val ntfy = spec.provider("ntfy")!!
    private val smtp = spec.provider("smtp")!!

    /** The recorded `notificationList` (Kuma 2.5.8) as [KumaNotification]s, decoded like core/data does. */
    private val recorded: List<KumaNotification> by lazy {
        val dir =
            System.getProperty("kuma.fixtures")?.let(::File)
                ?: File(System.getProperty("user.dir")).resolve("../../fixtures/kuma-2.5.8").normalize()
        val root =
            JsonValues.toKotlin(
                JsonValues.parseOrNull(File(dir, "notificationList.json").readText()),
            ) as Map<*, *>
        val rows =
            ((root["samples"] as List<*>).first() as Map<*, *>)["args"].let {
                (it as List<*>).first() as List<*>
            }
        rows.map { row ->
            row as Map<*, *>
            val config = JsonValues.toKotlin(JsonValues.parseOrNull(row["config"] as String)) as Map<*, *>
            KumaNotification(
                id = row["id"] as Long,
                name = row["name"] as String,
                type = config["type"] as String,
                isDefault = row["isDefault"] as Boolean,
                active = row["active"] as Boolean,
                fields =
                    config.entries
                        .associate { (k, v) -> k.toString() to v }
                        .filterKeys { it !in setOf("name", "type", "isDefault", "applyExisting", "active") },
            )
        }
    }

    private fun valid(
        provider: ProviderSpec,
        draft: NotificationDraft,
    ) = NotificationForm.validate(provider, draft).isEmpty()

    @Test
    fun `new draft gets defaults and a unique default name`() {
        val draft = NotificationForm.newDraft(ntfy, existingNames = listOf("My Ntfy Alert (1)"))
        assertEquals("My Ntfy Alert (2)", draft.name)
        assertEquals("ntfy", draft.type)
        assertNull(draft.id)
        assertEquals("https://ntfy.sh", draft.values["ntfyserverurl"])
        assertEquals(5L, draft.values["ntfyPriority"])
        assertEquals("none", draft.values["ntfyAuthenticationMethod"])
        assertFalse("ntfytopic" in draft.values)
    }

    @Test
    fun `required fields only count while visible`() {
        var draft = NotificationForm.newDraft(ntfy)
        val issues = NotificationForm.validate(ntfy, draft)
        assertEquals(listOf("ntfytopic" to NotificationIssue.Kind.REQUIRED), issues.map { it.field to it.kind })
        draft = draft.copy(values = draft.values + ("ntfytopic" to "upP3"))
        assertTrue(valid(ntfy, draft))
        // Hidden while the auth method is "none", visible (but optional) for username/password.
        assertFalse(NotificationForm.isVisible(ntfy.field("ntfyusername")!!, draft))
        draft = draft.copy(values = draft.values + ("ntfyAuthenticationMethod" to "usernamePassword"))
        assertTrue(NotificationForm.isVisible(ntfy.field("ntfyusername")!!, draft))
    }

    @Test
    fun `numbers - range and parse errors, normalised in the payload`() {
        val draft = NotificationForm.newDraft(ntfy).let { it.copy(values = it.values + ("ntfytopic" to "t")) }
        val tooHigh = draft.copy(values = draft.values + ("ntfyPriority" to "9"))
        val issue = NotificationForm.validate(ntfy, tooHigh).single()
        assertEquals(NotificationIssue.Kind.RANGE, issue.kind)
        assertEquals(1.0, issue.min)
        assertEquals(5.0, issue.max)
        val notANumber = draft.copy(values = draft.values + ("ntfyPriority" to "x"))
        assertEquals(NotificationIssue.Kind.NUMBER, NotificationForm.validate(ntfy, notANumber).single().kind)
        val typed = draft.copy(values = draft.values + ("ntfyPriority" to "3"))
        assertEquals(3L, NotificationForm.toNotification(ntfy, typed).fields["ntfyPriority"])
    }

    @Test
    fun `webhook - url, custom body visibility, headers toggle and json`() {
        var draft = NotificationForm.newDraft(webhook)
        assertEquals("post", draft.values["httpMethod"])
        assertEquals("json", draft.values["webhookContentType"])
        assertEquals(false, draft.toggles["showAdditionalHeadersField"])
        draft = draft.copy(values = draft.values + ("webhookURL" to "ftp://x"))
        assertEquals(NotificationIssue.Kind.URL, NotificationForm.validate(webhook, draft).single().kind)
        draft = draft.copy(values = draft.values + ("webhookURL" to "http://localhost:9"))
        assertTrue(valid(webhook, draft))

        draft = draft.copy(values = draft.values + ("webhookContentType" to "custom"))
        assertEquals("webhookCustomBody", NotificationForm.validate(webhook, draft).single().field)
        draft = draft.copy(values = draft.values + ("httpMethod" to "get"))
        assertTrue(valid(webhook, draft), "GET hides content type and body")

        draft =
            draft.copy(
                toggles = mapOf("showAdditionalHeadersField" to true),
                values =
                    draft.values + ("webhookAdditionalHeaders" to "{x"),
            )
        assertEquals(NotificationIssue.Kind.JSON, NotificationForm.validate(webhook, draft).single().kind)
        draft = draft.copy(values = draft.values + ("webhookAdditionalHeaders" to """{"A":"b"}"""))
        assertTrue(valid(webhook, draft))
        assertEquals(
            """{"A":"b"}""",
            NotificationForm.toNotification(webhook, draft).fields["webhookAdditionalHeaders"],
        )
        // Switching the headers off sends null (Kuma: "to disable, store null").
        val off = draft.copy(toggles = mapOf("showAdditionalHeadersField" to false))
        val fields = NotificationForm.toNotification(webhook, off).fields
        assertTrue("webhookAdditionalHeaders" in fields)
        assertNull(fields["webhookAdditionalHeaders"])
    }

    @Test
    fun `smtp - starttls option hidden for tls, from required, port range`() {
        var draft = NotificationForm.newDraft(smtp)
        assertEquals(false, draft.values["smtpSecure"])
        assertTrue(NotificationForm.isVisible(smtp.field("smtpIgnoreSTARTTLS")!!, draft))
        draft = draft.copy(values = draft.values + ("smtpSecure" to true))
        assertFalse(NotificationForm.isVisible(smtp.field("smtpIgnoreSTARTTLS")!!, draft))
        assertEquals(
            setOf("smtpHost", "smtpPort", "smtpFrom"),
            NotificationForm.validate(smtp, draft).map { it.field }.toSet(),
        )
        draft =
            draft.copy(values = draft.values + mapOf("smtpHost" to "h", "smtpPort" to "70000", "smtpFrom" to "a@b.c"))
        assertEquals(NotificationIssue.Kind.RANGE, NotificationForm.validate(smtp, draft).single().kind)
    }

    @Test
    fun `patterns use whole-value html semantics`() {
        val threema = spec.provider("threema")!!
        var draft = NotificationForm.newDraft(threema)
        draft =
            draft.copy(
                values =
                    draft.values +
                        mapOf(
                            "threemaRecipientType" to "email",
                            "threemaRecipient" to "a@b.c",
                            "threemaSenderIdentity" to "*ABC1234",
                            "threemaSecret" to "s",
                        ),
            )
        assertTrue(valid(threema, draft), "threemaRecipient has alt types: no pattern check")
        draft = draft.copy(values = draft.values + ("threemaSenderIdentity" to "ABC1234X"))
        assertEquals(NotificationIssue.Kind.PATTERN, NotificationForm.validate(threema, draft).single().kind)
    }

    @Test
    fun `name is required and trimmed`() {
        val pushbullet = spec.provider("pushbullet")!!
        val draft = NotificationForm.newDraft(pushbullet).copy(values = mapOf("pushbulletAccessToken" to "x"))
        assertEquals(
            NotificationIssue.Kind.REQUIRED,
            NotificationForm.validate(pushbullet, draft.copy(name = " ")).single().kind,
        )
        assertEquals("Mine", NotificationForm.toNotification(pushbullet, draft.copy(name = " Mine ")).name)
    }

    @Test
    fun `recorded configs round-trip unchanged, unknown fields included`() {
        assertEquals(listOf("webhook", "ntfy"), recorded.map { it.type })
        for (notification in recorded) {
            val provider = spec.provider(notification.type)!!
            val draft = NotificationForm.fromNotification(notification, provider)
            assertFalse(draft.applyExisting)
            val back = NotificationForm.toNotification(provider, draft)
            assertEquals(notification, back, notification.name)
        }
        // ntfyCall is a text field but stored as false; httpMethod is "POST" upper-case. Both survive.
        val ntfyDraft = NotificationForm.fromNotification(recorded[1], ntfy)
        assertEquals(false, NotificationForm.toNotification(ntfy, ntfyDraft).fields["ntfyCall"])
        assertTrue(valid(ntfy, ntfyDraft))
        val hook = NotificationForm.fromNotification(recorded[0], webhook)
        assertEquals("post", NotificationForm.matchOption(webhook.field("httpMethod")!!, "POST")?.value)
        assertTrue(NotificationForm.isVisible(webhook.field("webhookContentType")!!, hook))
        // Empty headers string: the switch starts off and the stored "" is kept as it is.
        assertEquals(false, hook.toggles["showAdditionalHeadersField"])
        assertEquals("", NotificationForm.toNotification(webhook, hook).fields["webhookAdditionalHeaders"])
    }

    @Test
    fun `keeps keys the spec does not know`() {
        val n =
            KumaNotification(
                id = 7,
                name = "Legacy",
                type = "discord",
                fields =
                    mapOf(
                        "discordWebhookUrl" to "https://d",
                        "discordUseMessageTemplate" to true,
                        "future" to listOf(1L),
                    ),
            )
        val back =
            NotificationForm.toNotification(
                spec.provider("discord"),
                NotificationForm.fromNotification(n, spec.provider("discord")),
            )
        assertEquals(true, back.fields["discordUseMessageTemplate"])
        assertEquals(listOf(1L), back.fields["future"])
        // An unknown provider type is passed through as is.
        val unknown = n.copy(type = "brandNew")
        assertEquals(unknown, NotificationForm.toNotification(null, NotificationForm.fromNotification(unknown, null)))
    }

    @Test
    fun `multiselect text becomes a list`() {
        val ding = spec.provider("DingDing")!!
        val draft =
            NotificationForm.newDraft(ding).copy(
                values =
                    mapOf(
                        "webHookUrl" to "https://x",
                        "secretKey" to "k",
                        "mentioning" to "specify-mobiles",
                        "mobileList" to "1, 2\n3",
                    ),
            )
        assertEquals(listOf("1", "2", "3"), NotificationForm.toNotification(ding, draft).fields["mobileList"])
    }

    @Test
    fun `this-device push webhook detection`() {
        val device =
            KumaNotification(
                id = 3,
                name = "Pixel 9 (UnifiedPush)",
                type = "webhook",
                fields = mapOf("webhookURL" to "http://kuma-dev-ntfy:80/upABC?up=1"),
            )
        assertTrue(NotificationForm.isThisDevicePush(device))
        assertFalse(NotificationForm.isThisDevicePush(device.copy(name = "Pixel 9")))
        assertFalse(NotificationForm.isThisDevicePush(device.copy(fields = mapOf("webhookURL" to "http://x/upABC"))))
        assertFalse(NotificationForm.isThisDevicePush(recorded[0]))
    }
}
