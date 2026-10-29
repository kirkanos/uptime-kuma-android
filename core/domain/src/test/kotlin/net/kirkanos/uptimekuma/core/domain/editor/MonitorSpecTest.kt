package net.kirkanos.uptimekuma.core.domain.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorSpecTest {
    private val spec = MonitorSpec.bundled

    @Test
    fun `bundled spec has all 34 types and 117 fields`() {
        assertEquals("2.5.8", spec.kumaVersion)
        assertEquals(34, spec.types.size)
        assertEquals(117, spec.fields.size)
        assertEquals("http", spec.types.first().id)
        assertTrue(spec.readonlyKeys.containsAll(listOf("id", "tags", "pathName")))
        assertTrue("accepted_statuscodes" in spec.editCopiedKeys)
    }

    @Test
    fun `every field listed for a type exists in the catalogue and has a widget`() {
        for (type in spec.types) {
            for (name in type.fields) {
                val field = spec.field(name)
                checkNotNull(field) { "${type.id}: unknown field $name" }
                if (name !in setOf("type", "active")) {
                    assertFalse(field.kind == FieldKind.HIDDEN, "${type.id}.$name has no widget (${field.rawType})")
                }
            }
        }
    }

    @Test
    fun `every visible_if of the catalogue is translated into a rule`() {
        val missing = spec.fields.values.filter { it.visibleIf != null && it.name !in MonitorFieldRules.ruleNames }
        assertTrue(missing.isEmpty(), "no visibility rule for ${missing.map { it.name }}")
    }

    @Test
    fun `type groups follow the web UI optgroups`() {
        assertEquals(TypeGroup.GENERAL, spec.type("http")?.group)
        assertEquals(TypeGroup.PASSIVE, spec.type("push")?.group)
        assertEquals(TypeGroup.SPECIFIC, spec.type("mqtt")?.group)
        assertEquals(TypeGroup.DATABASE, spec.type("postgres")?.group)
        assertEquals(TypeGroup.GAME_SERVER, spec.type("steam")?.group)
        assertEquals(TypeGroup.OTHER, spec.type("group")?.group)
    }

    @Test
    fun `options are resolved per type and globalping subtype`() {
        val method = spec.field("method")!!
        assertEquals(7, method.optionsFor("http").size)
        assertEquals(listOf("HEAD", "GET", "OPTIONS"), method.optionsFor("globalping", "http"))
        val dnsType = spec.field("dns_resolve_type")!!
        assertEquals(10, dnsType.optionsFor("dns").size)
        assertTrue("DNSKEY" in dnsType.optionsFor("globalping", "dns"))
        assertEquals(listOf("ICMP", "TCP"), spec.field("protocol")!!.optionsFor("globalping", "ping"))
        assertTrue(null in spec.field("authMethod")!!.optionsFor("websocket-upgrade"))
        assertFalse("ntlm" in spec.field("authMethod")!!.optionsFor("websocket-upgrade"))
    }

    @Test
    fun `condition variables come from the spec`() {
        val dns = spec.type("dns")!!
        assertTrue(dns.supportsConditions)
        assertEquals("record", dns.conditionVariables.single().id)
        assertEquals(spec.stringOperators, dns.conditionVariables.single().operatorIds(spec))
        val mqtt = spec.type("mqtt")!!.conditionVariables.first { it.id == "json_value" }
        assertEquals(spec.stringOperators + spec.numberOperators, mqtt.operatorIds(spec))
        assertFalse(spec.type("http")!!.supportsConditions)
    }

    @Test
    fun `numeric ui defaults of the spec match the draft defaults`() {
        for (type in spec.types) {
            val draft = MonitorDrafts.newDraft(spec, type.id)
            val port = draft["port"]
            MonitorDrafts.PORT_DEFAULTS[type.id]?.let { assertEquals(it, port, type.id) }
            val expectedTimeout = MonitorDrafts.TIMEOUT_DEFAULTS[type.id] ?: 48.0
            assertEquals(expectedTimeout, draft["timeout"].asDouble(), type.id)
        }
        assertEquals(300L, MonitorDrafts.newDraft(spec, "ntp")["interval"])
        assertEquals("1.1.1.1", MonitorDrafts.newDraft(spec, "dns")["dns_resolve_server"])
        assertEquals("wss://", MonitorDrafts.newDraft(spec, "websocket-upgrade")["url"])
        assertEquals(listOf("1000"), MonitorDrafts.newDraft(spec, "websocket-upgrade")["accepted_statuscodes"])
    }
}
