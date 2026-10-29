package net.kirkanos.uptimekuma.feature.notifications

import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderCategory
import net.kirkanos.uptimekuma.feature.notifications.edit.headerPairs
import net.kirkanos.uptimekuma.feature.notifications.edit.headersJson
import net.kirkanos.uptimekuma.feature.notifications.picker.ProviderCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderPresentationTest {
    private val spec = NotificationProviderSpec.bundled

    /** The ~15 providers with hand-written texts. */
    private val curated =
        listOf(
            "telegram",
            "discord",
            "slack",
            "smtp",
            "ntfy",
            "gotify",
            "pushover",
            "webhook",
            "matrix",
            "teams",
            "signal",
            "apprise",
            "HomeAssistant",
            "pushbullet",
            "mattermost",
        )

    @Test
    fun `hand-written texts match the spec and cover every field of the curated providers`() {
        for (key in ProviderTexts.fields.keys + ProviderTexts.options.keys) {
            val (type, field) = key.split('.', limit = 2)
            assertTrue(spec.provider(type)?.field(field) != null, "no spec field for $key")
        }
        for (type in curated) {
            val provider = requireNotNull(spec.provider(type)) { type }
            for (field in provider.fields) {
                assertTrue(ProviderTexts.field(type, field) != null, "no label for $type.${field.name}")
            }
        }
        for ((key, labels) in ProviderTexts.options) {
            val (type, name) = key.split('.', limit = 2)
            val values =
                spec
                    .provider(type)!!
                    .field(name)!!
                    .options
                    .map { it.value.toString() }
            assertTrue(values.containsAll(labels.keys), "$key: ${labels.keys} not in $values")
        }
    }

    @Test
    fun `curated layouts list existing fields once and lose none`() {
        for ((type, names) in ProviderLayouts.curatedFieldNames) {
            val provider = requireNotNull(spec.provider(type)) { type }
            assertEquals(names.toSet().size, names.size, "duplicate in $type layout")
            names.forEach { assertTrue(provider.field(it) != null, "$type layout: unknown field $it") }
            val sections = ProviderLayouts.sections(provider)
            assertEquals(
                provider.fields.map { it.name }.sorted(),
                sections
                    .flatMap { s ->
                        s.fields.map { it.name }
                    }.sorted(),
            )
            assertTrue(sections.none { it.title == R.string.notif_section_more }, "$type layout misses fields")
        }
        val generic = ProviderLayouts.sections(spec.provider("SMSEagle")!!)
        assertEquals(1, generic.size)
        assertNull(generic.single().title)
    }

    @Test
    fun `every provider gets a section for every field`() {
        for (provider in spec.providers) {
            val names = ProviderLayouts.sections(provider).flatMap { s -> s.fields.map { it.name } }
            assertEquals(provider.fields.size, names.size, provider.id)
        }
    }

    @Test
    fun `generic labels drop the provider prefix and split words`() {
        assertEquals("Bot Token", FieldLabels.humanize("telegram", "telegramBotToken"))
        assertEquals("API Key", FieldLabels.humanize("SevenIO", "sevenioApiKey"))
        assertEquals("Dkim Domain", FieldLabels.humanize("smtp", "smtpDkimDomain"))
        assertEquals("Access Key ID", FieldLabels.humanize("AliyunSMS", "accessKeyId"))
        assertEquals("Phonenumber", FieldLabels.humanize("AliyunSMS", "phonenumber"))
        assertEquals("Sounds up", FieldLabels.humanize("pushover", "pushoversounds_up"))
        assertEquals("Integration URL", FieldLabels.humanize("PagerTree", "pagertreeIntegrationUrl"))
        assertEquals("Chat ID", FieldLabels.humanize("waha", "wahaChatId"))
        // Field equal to the id is not stripped to nothing.
        assertEquals("Ntfy", FieldLabels.humanize("ntfy", "ntfy"))
    }

    @Test
    fun `picker groups by category and searches label and id`() {
        val groups = ProviderCatalog.groups(spec, "")
        assertEquals(ProviderCatalog.order, groups.map { it.first })
        // Providers in two categories show up twice, like in the web UI.
        assertTrue(groups.sumOf { it.second.size } > spec.providers.size)
        assertEquals(spec.providers.map { it.id }.toSet(), groups.flatMap { g -> g.second.map { it.id } }.toSet())
        assertEquals(
            listOf("HomeAssistant"),
            groups
                .first {
                    it.first == ProviderCategory.HOME_AUTOMATION
                }.second
                .map { it.id },
        )

        val hits = ProviderCatalog.groups(spec, " teleg ")
        assertNull(hits.single().first)
        assertEquals(
            listOf("CallMeBot", "telegram"),
            hits.single().second.map {
                it.id
            },
        ) // "CallMeBot (WhatsApp, Telegram Call, ...)"
        assertTrue(
            ProviderCatalog
                .groups(spec, "rocket.chat")
                .single()
                .second
                .any { it.id == "rocket.chat" },
        )
        assertTrue(ProviderCatalog.groups(spec, "zzzz").isEmpty())
    }

    @Test
    fun `headers are edited as pairs when the json is a flat object`() {
        assertEquals(listOf("Authorization" to "Bearer x"), headerPairs("""{"Authorization":"Bearer x"}"""))
        assertEquals(emptyList<Pair<String, String>>(), headerPairs(""))
        assertEquals(emptyList<Pair<String, String>>(), headerPairs(null))
        assertNull(headerPairs("{oops"))
        assertNull(headerPairs("""{"a":{"b":1}}"""))
        assertEquals("""{"A":"1","B":""}""", headersJson(listOf("A" to "1", " " to "dropped", "B" to "")))
        assertEquals("", headersJson(listOf("" to "x")))
    }
}
