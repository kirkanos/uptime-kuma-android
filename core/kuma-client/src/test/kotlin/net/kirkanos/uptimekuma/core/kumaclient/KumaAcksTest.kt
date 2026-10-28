package net.kirkanos.uptimekuma.core.kumaclient

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kirkanos.uptimekuma.core.model.KumaError
import net.kirkanos.uptimekuma.core.model.KumaException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KumaAcksTest {
    @ParameterizedTest
    @CsvSource(
        "authIncorrectCreds, INVALID_CREDENTIALS",
        "authInvalidToken, INVALID_CREDENTIALS",
        "authUserInactiveOrDeleted, INVALID_CREDENTIALS",
        "'Too frequently, try again later.', RATE_LIMITED",
        "You are not logged in., UNAUTHORIZED",
        "Permission denied., SERVER_REJECTED",
        "successAdded, SERVER_REJECTED",
    )
    fun `ok false maps by message`(
        message: String,
        kind: KumaError.Kind,
    ) {
        val ack =
            buildJsonObject {
                put("ok", false)
                put("msg", message)
            }
        val error = assertThrows<KumaException> { KumaAcks.requireOk("x", ack) }.error
        assertEquals(kind, error.kind)
        assertEquals(message, error.message)
    }

    @Test
    fun `msg object form yields the i18n key`() {
        val ack =
            buildJsonObject {
                put("ok", false)
                put("msgi18n", true)
                put("msg", buildJsonObject { put("key", "foundChromiumVersion") })
            }
        assertEquals("foundChromiumVersion", KumaAcks.errorOf(ack).message)
    }

    @Test
    fun `ok as 1 is accepted, missing ok and non objects are UNKNOWN`() {
        KumaAcks.requireOk("x", buildJsonObject { put("ok", 1) })
        assertEquals(
            KumaError.Kind.UNKNOWN,
            assertThrows<KumaException> { KumaAcks.requireOk("x", buildJsonObject { put("msg", "?") }) }.error.kind,
        )
        assertEquals(
            KumaError.Kind.UNKNOWN,
            assertThrows<KumaException> {
                KumaAcks.requireOk("x", JsonPrimitive(true))
            }.error.kind,
        )
        assertEquals(
            KumaError.Kind.UNKNOWN,
            assertThrows<KumaException> { KumaAcks.requireOk("x", JsonNull) }.error.kind,
        )
        assertEquals(KumaError.Kind.UNKNOWN, assertThrows<KumaException> { KumaAcks.requireOk("x", null) }.error.kind)
    }

    @Test
    fun `ok false without msg is SERVER_REJECTED`() {
        val error = assertThrows<KumaException> { KumaAcks.requireOk("x", buildJsonObject { put("ok", false) }) }.error
        assertEquals(KumaError.Kind.SERVER_REJECTED, error.kind)
    }

    @Test
    fun `login without token in success ack is rejected`() {
        assertThrows<KumaException> { KumaAcks.loginResponse(buildJsonObject { put("ok", true) }) }
    }
}
