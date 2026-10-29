package net.kirkanos.uptimekuma.feature.monitors.editor

import net.kirkanos.uptimekuma.core.domain.editor.FieldKind
import net.kirkanos.uptimekuma.core.domain.editor.MonitorDrafts
import net.kirkanos.uptimekuma.core.domain.editor.MonitorSpec
import net.kirkanos.uptimekuma.core.domain.editor.TypeGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorLogicTest {
    private val spec = MonitorSpec.bundled

    private fun sections(
        type: String,
        vararg extra: Pair<String, Any?>,
    ) = EditorSections.build(spec, MonitorDrafts.newDraft(spec, type).fields + extra).associate { s ->
        s.kind to
            s.fields.map { it.name }
    }

    @Test
    fun `http sections`() {
        val s = sections("http", "authMethod" to "bearer")
        assertEquals(listOf("name", "parent", "description"), s[SectionKind.GENERAL])
        assertEquals(listOf("url"), s[SectionKind.TYPE])
        assertEquals(listOf("authMethod", "bearer_token"), s[SectionKind.AUTH])
        assertEquals(listOf("headers", "method", "httpBodyEncoding", "body"), s[SectionKind.HTTP_REQUEST])
        assertEquals(listOf("proxyId"), s[SectionKind.PROXY])
        assertTrue(s.getValue(SectionKind.ADVANCED).contains("accepted_statuscodes"))
        assertTrue(SectionKind.TAGS in s && SectionKind.NOTIFICATIONS in s)
    }

    @Test
    fun `group has no type section and push shows its token`() {
        val group = sections("group")
        assertFalse(SectionKind.TYPE in group)
        assertFalse(SectionKind.AUTH in group)
        assertEquals(listOf("pushToken"), sections("push")[SectionKind.TYPE])
        assertEquals(
            listOf("databaseConnectionString", "basic_auth_user", "basic_auth_pass", "databaseQuery", "conditions"),
            sections("oracledb")[SectionKind.TYPE],
        )
    }

    @Test
    fun `every visible field of every type has a label and a widget`() {
        for (type in spec.types) {
            for (section in EditorSections.build(spec, MonitorDrafts.newDraft(spec, type.id).fields)) {
                for (field in section.fields) {
                    assertTrue(hasFieldLabel(field.name), "${type.id}.${field.name} has no label")
                    assertFalse(field.kind == FieldKind.HIDDEN, "${type.id}.${field.name} hidden")
                }
            }
        }
    }

    @Test
    fun `type picker groups all types`() {
        val groups = groupedTypes(spec)
        assertEquals(34, groups.sumOf { it.second.size })
        assertEquals(TypeGroup.GENERAL, groups.first().first)
        assertTrue(groups.any { (g, t) -> g == TypeGroup.PASSIVE && t.map { it.id } == listOf("push", "manual") })
        assertTrue(groups.any { (g, t) -> g == TypeGroup.OTHER && t.map { it.id } == listOf("group") })
    }

    @Test
    fun `headers convert between rows and JSON`() {
        assertEquals(emptyList<Pair<String, String>>(), headerRows(null))
        assertEquals(listOf("Accept" to "text/html", "X-N" to "1"), headerRows("{\"Accept\":\"text/html\",\"X-N\":1}"))
        assertNull(headerRows("{\"a\":{\"b\":1}}"))
        assertNull(headerRows("not json"))
        assertEquals("{\n    \"Accept\": \"*/*\"\n}", headersJson(listOf("Accept" to "*/*", "" to "ignored")))
        assertNull(headersJson(listOf("" to "")))
    }

    @Test
    fun `display text of values`() {
        assertEquals("", displayText(null))
        assertEquals("60", displayText(60.0))
        assertEquals("12.5", displayText(12.5))
        assertEquals("a\nb", displayText(listOf("a", "b")))
    }

    @Test
    fun `new condition uses the first variable and operator`() {
        assertEquals(
            mapOf(
                "type" to "expression",
                "variable" to "record",
                "operator" to "equals",
                "value" to "",
                "andOr" to "and",
            ),
            newCondition(spec, "dns"),
        )
    }
}
