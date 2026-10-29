package net.kirkanos.uptimekuma.core.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConditionTest {
    private fun eval(
        text: String,
        values: Map<String, Any?>,
        toggles: Map<String, Boolean> = emptyMap(),
    ): Boolean = requireNotNull(Condition.parseOrNull(text)) { text }.evaluate(values, toggles)

    @Test
    fun `identifiers use javascript truthiness`() {
        assertFalse(eval("a", emptyMap()))
        assertFalse(eval("a", mapOf("a" to "")))
        assertFalse(eval("a", mapOf("a" to 0L)))
        assertTrue(eval("a", mapOf("a" to "x")))
        assertTrue(eval("a", mapOf("a" to true)))
        assertTrue(eval("!a", mapOf("a" to false)))
        assertTrue(eval("!a", emptyMap()))
    }

    @Test
    fun `equality is lenient on types and case`() {
        assertTrue(eval("httpMethod === 'post'", mapOf("httpMethod" to "post")))
        assertTrue(eval("httpMethod === 'post'", mapOf("httpMethod" to "POST")))
        assertFalse(eval("httpMethod === 'post'", mapOf("httpMethod" to "get")))
        assertTrue(eval("p == 5", mapOf("p" to "5")))
        assertTrue(eval("p !== 'group'", emptyMap()))
        assertTrue(eval("p != \"group\"", mapOf("p" to "phone")))
    }

    @Test
    fun `and binds tighter than or`() {
        val text = "t === 'v1' && m === 'sms' || r !== 'to'"
        assertTrue(eval(text, mapOf("t" to "v1", "m" to "sms", "r" to "to")))
        assertTrue(eval(text, mapOf("t" to "v2", "m" to "ring", "r" to "group")))
        assertFalse(eval(text, mapOf("t" to "v2", "m" to "sms", "r" to "to")))
        assertTrue(eval("!a || b", mapOf("a" to true, "b" to true)))
        assertFalse(eval("!a || b", mapOf("a" to true, "b" to false)))
        assertTrue(eval("(a || b) && c", mapOf("b" to true, "c" to true)))
    }

    @Test
    fun `ui toggles override values`() {
        assertTrue(eval("showAdditionalHeadersField", emptyMap(), mapOf("showAdditionalHeadersField" to true)))
        assertFalse(
            eval(
                "showAdditionalHeadersField",
                mapOf("showAdditionalHeadersField" to true),
                mapOf("showAdditionalHeadersField" to false),
            ),
        )
    }

    @Test
    fun `identifiers are collected and bad syntax is rejected`() {
        assertEquals(setOf("a", "b"), Condition.parseOrNull("a === 'x' && !b")!!.identifiers())
        assertNull(Condition.parseOrNull("a ? b : c"))
        assertNull(Condition.parseOrNull("a === 'x"))
        assertNull(Condition.parseOrNull("(a"))
    }

    @Test
    fun `unparseable visibility degrades to always visible`() {
        assertEquals(Visibility.Always, Visibility.of("a ? b : c", emptyList()))
        assertEquals(Visibility.Always, Visibility.of(null, listOf("a", "else")))
        assertTrue(Visibility.of(null, listOf("a", "b")) is Visibility.AnyOf)
    }
}
