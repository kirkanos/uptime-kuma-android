package net.kirkanos.uptimekuma.core.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationProviderSpecTest {
    private val spec = NotificationProviderSpec.bundled

    @Test
    fun `bundled spec has all 108 providers and 433 fields`() {
        assertEquals("2.5.8", spec.kumaVersion)
        assertEquals(108, spec.providers.size)
        assertEquals(433, spec.providers.sumOf { it.fields.size })
        assertEquals(spec.providers.map { it.label.lowercase() }.sorted(), spec.providers.map { it.label.lowercase() })
    }

    @Test
    fun `every provider has a category and every field a widget`() {
        for (provider in spec.providers) {
            assertTrue(provider.categories.isNotEmpty(), provider.id)
            for (field in provider.fields) {
                if (provider.id != "Webpush") {
                    assertFalse(
                        field.kind == ProviderFieldKind.UNSUPPORTED,
                        "${provider.id}.${field.name} (${field.rawType})",
                    )
                }
            }
        }
        assertFalse(spec.provider("Webpush")!!.configurable)
        assertTrue(spec.provider("ntfy")!!.configurable)
    }

    @Test
    fun `every category is used and the known ones are mapped`() {
        val used = spec.providers.flatMap { it.categories }.toSet()
        assertEquals(ProviderCategory.entries.toSet(), used)
        assertEquals(listOf(ProviderCategory.CHAT, ProviderCategory.PUSH), spec.provider("gotify")!!.categories)
        assertEquals(1, spec.providersIn(ProviderCategory.HOME_AUTOMATION).size)
    }

    @Test
    fun `every visibility condition parses and only references fields or ui toggles`() {
        for (provider in spec.providers) {
            for (field in provider.fields) {
                val v = field.visibility
                if (field.name == "cellsyntOriginator") {
                    // v-if/v-else chain: shown in every branch.
                    assertEquals(Visibility.Always, v, field.name)
                }
                for (id in v.identifiers()) {
                    assertTrue(
                        provider.field(id) != null || id == "showAdditionalHeadersField",
                        "${provider.id}.${field.name} references unknown $id",
                    )
                }
            }
        }
        val conditional = spec.providers.flatMap { p -> p.fields.filter { it.visibility != Visibility.Always } }
        // 42 visible_if + 6 visible_if_any (the 7th ends in "else" and is always visible).
        assertEquals(48, conditional.size)
        assertEquals(setOf("showAdditionalHeadersField"), spec.provider("webhook")!!.uiToggles)
        assertEquals(setOf("showAdditionalHeadersField"), spec.provider("smtp")!!.uiToggles)
        assertTrue(spec.provider("telegram")!!.uiToggles.isEmpty())
    }

    @Test
    fun `lookup is case-insensitive as a fallback`() {
        assertEquals("HomeAssistant", spec.provider("homeassistant")?.id)
        assertEquals("Home Assistant", spec.provider("HomeAssistant")?.label)
        assertNull(spec.provider("nope"))
    }

    @Test
    fun `smtp security options are booleans`() {
        val secure = spec.provider("smtp")!!.field("smtpSecure")!!
        assertEquals(listOf(false, true), secure.options.map { it.value })
        assertEquals("None / STARTTLS (25, 587)", secure.options.first().label)
        assertEquals(false, secure.default)
    }

    @Test
    fun `defaults - literals, first of a list, curated overrides, js expressions dropped`() {
        val ntfy = spec.provider("ntfy")!!
        assertEquals("https://ntfy.sh", ntfy.field("ntfyserverurl")!!.default)
        assertEquals(5L, ntfy.field("ntfyPriority")!!.default)
        assertEquals("none", ntfy.field("ntfyAuthenticationMethod")!!.default)
        assertEquals(false, ntfy.field("ntfyUseTemplate")!!.default)
        assertEquals("normal", spec.provider("discord")!!.field("discordMessageFormat")!!.default)
        val chatId = spec.provider("telegram")!!.field("telegramChatID")!!
        assertFalse(chatId.hasDefault)
        assertEquals("post", spec.provider("webhook")!!.field("httpMethod")!!.default)
    }

    @Test
    fun `placeholders and option labels are cleaned`() {
        val discord = spec.provider("discord")!!
        assertEquals("Uptime Kuma", discord.field("discordUsername")!!.placeholder)
        assertEquals("Hello @everyone is...", discord.field("discordPrefixMessage")!!.placeholder)
        assertEquals("e.g. 1177566663751782411", discord.field("threadId")!!.placeholder)
        assertNull(spec.provider("webhook")!!.field("webhookAdditionalHeaders")!!.placeholder)
        assertEquals(
            "\"Uptime Kuma\" <example@kuma.pet>",
            spec.provider("smtp")!!.field("smtpFrom")!!.placeholder,
        )
        val octopush = spec.provider("octopush")!!.field("octopushVersion")!!
        assertEquals("api.octopush.com", octopush.options.first().label)
        assertTrue(
            spec.providers
                .flatMap { it.fields }
                .flatMap { it.options }
                .none { it.label.contains("\$t(") },
        )
        assertTrue(
            spec.providers
                .flatMap { it.fields }
                .mapNotNull { it.placeholder }
                .none { it.contains("\$t(") },
        )
    }

    @Test
    fun `field kinds`() {
        val webhook = spec.provider("webhook")!!
        assertEquals(ProviderFieldKind.URL, webhook.field("webhookURL")!!.kind)
        assertEquals(ProviderFieldKind.JSON_OBJECT, webhook.field("webhookAdditionalHeaders")!!.kind)
        assertEquals(ProviderFieldKind.TEMPLATE_MULTILINE, webhook.field("webhookCustomBody")!!.kind)
        assertEquals(ProviderFieldKind.TEMPLATE, spec.provider("smtp")!!.field("customSubject")!!.kind)
        assertEquals(ProviderFieldKind.LIST, spec.provider("DingDing")!!.field("mobileList")!!.kind)
        assertEquals(ProviderFieldKind.EMAIL, spec.provider("JiraServiceManagement")!!.field("jsmEmail")!!.kind)
        assertEquals(ProviderFieldKind.PASSWORD, spec.provider("telegram")!!.field("telegramBotToken")!!.kind)
    }

    @Test
    fun `short label drops the explanation`() {
        assertEquals("Apprise", spec.provider("apprise")!!.shortLabel)
        assertEquals("Email", spec.provider("smtp")!!.shortLabel)
        assertEquals("Telegram", spec.provider("telegram")!!.shortLabel)
    }
}
